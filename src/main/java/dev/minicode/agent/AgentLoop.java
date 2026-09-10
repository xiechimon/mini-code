package dev.minicode.agent;

import dev.minicode.ai.Context;
import dev.minicode.ai.LlmClient;
import dev.minicode.ai.Message;
import dev.minicode.ai.Model;
import dev.minicode.tools.ToolDefinition;
import dev.minicode.tools.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 最小 Agent 循环，忠实对齐 pi 的 packages/agent/src/agent-loop.ts。
 * 简化版：单层循环，不含 steering/followUp；工具按 ToolKind 分组执行（见下）。
 * 通过 EventSink 对外发射事件，便于观测与后续 TUI 接入。
 * <p>
 * 03 票起统一走 {@link LlmClient#stream} 流式路径：每个文本片段回调即发射
 * {@link AgentEvent.MessageUpdate}，片段累积为完整文本后走既有 MessageEnd 路径；
 * 工具调用分发的完整 JSON 仍从拼装结果取。default stream 在零回调时等价同步，
 * 因此既有 fake 测试零改动；中断触发器可注入，置位即通过 stream 的取消参数生效。
 * </p>
 * <p>
 * 工具循环在每次执行前后走同步 {@link ToolHook} 链（对齐 pi wiki/19 beforeToolCall/afterToolCall，
 * 有意简化见 docs/adr/0005）：按注册顺序票决，BLOCK 短路该工具、MODIFY 替换调用后续钩子看到修改后的，
 * 钩子自身异常 = 该工具 isError 失败；默认空 hooks 时行为与 MVP1 完全一致。
 * </p>
 * <p>
 * 并行执行（ticket 02，见 docs/adr/0005）：同回合多个工具调用按 LLM 发出顺序切连续段（runs），
 * 段内全 READ_ONLY 则经 daemon 线程池并行、含任一 STATEFUL 则整段按序串行，段间保持先后；
 * 结果严格按 LLM 发出顺序回 LLM。并行组内任一工具 execute 抛异常 → fail-fast cancel 兄弟
 * （被取消者构造 isError「cancelled」结果，已完成结果保留）；钩子的 BLOCK / isError 结果不算异常、不触发。
 * {@code ToolStart} / {@code ToolResultEvent} 按完成时序发射（并行下可交错，经事件锁保证 sink 不被并发调用）。
 * 线程池首次 {@code toolCalls.size() > 1} 时懒创建，daemon 随 JVM 退出，AgentLoop 不实现 AutoCloseable。
 * </p>
 */
public class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);
    /** 并行组内被取消工具的失败结果文本（fail-fast，见 docs/adr/0005） */
    private static final String CANCELLED_OUTPUT = "cancelled: 同组工具失败";
    /** 工具执行阶段被中止时，未执行工具的失败结果文本 */
    private static final String ABORTED_OUTPUT = "cancelled: 执行已中止";

    private final LlmClient llm;
    private final Model model;
    private final String systemPrompt;
    private final List<ToolDefinition> tools;
    private final int maxTurns; // 最大轮次，防止无限循环
    private final List<ToolHook> hooks; // 工具钩子链（before/after），默认空 = 行为与 MVP1 一致
    private final Supplier<InterruptTrigger> triggerSupplier;
    private final boolean streamingEnabled; // 管道模式禁用流式，零流式事件发射
    private final Object eventLock = new Object(); // 事件发射锁：并行工具下 sink 回调串行化（顺序可交错、调用不并发）
    // 并行工具的 daemon 线程池：首次同回合多工具（toolCalls.size() > 1）才懒创建；不实现 AutoCloseable，
    // daemon 线程随 JVM 退出（对齐 OpenAiCompatClient.doStream 的 monitor 线程模式，见 docs/adr/0005）
    private volatile ExecutorService toolExecutor;
    private final AtomicInteger toolThreadSeq = new AtomicInteger();

    public AgentLoop(LlmClient llm, Model model, String systemPrompt, List<ToolDefinition> tools, int maxTurns) {
        this(llm, model, systemPrompt, tools, maxTurns, (Supplier<InterruptTrigger>) null, true);
    }

    /**
     * 带中断触发器的构造——每次 stream 前通过 supplier 获取触发器，完成后释放。
     * 触发器通过 {@link InterruptTrigger#isCancelled()} 透传给 {@link LlmClient#stream} 的取消参数；
     * CLI 的 SIGINT 触发器在 04 票注册，本票仅做抽象与接线。
     *
     * @param triggerSupplier 触发器供应方，每次 stream 前调用 {@code get()} 获取，流结束后 {@code close()} 释放；可为 null 表示永不取消
     */
    public AgentLoop(LlmClient llm, Model model, String systemPrompt, List<ToolDefinition> tools, int maxTurns,
                     Supplier<InterruptTrigger> triggerSupplier) {
        this(llm, model, systemPrompt, tools, maxTurns, triggerSupplier, true);
    }

    /**
     * 带流式开关的构造——管道模式（非 tty）传 false 走同步 chat 等价路径，零流式事件发射。
     *
     * @param streamingEnabled false 时禁用流式，streamOnce 直接委派 chat，不发射 MessageUpdate
     */
    public AgentLoop(LlmClient llm, Model model, String systemPrompt, List<ToolDefinition> tools, int maxTurns,
                     Supplier<InterruptTrigger> triggerSupplier, boolean streamingEnabled) {
        this(llm, model, systemPrompt, tools, maxTurns, List.of(), triggerSupplier, streamingEnabled);
    }

    /**
     * 带工具钩子的构造（MVP2 钩子缝）——null 钩子列表视为空。默认启用流式、不带中断触发器。
     *
     * @param hooks 工具钩子链，按序在每次工具执行前后调用；可为 null 表示无钩子
     */
    public AgentLoop(LlmClient llm, Model model, String systemPrompt, List<ToolDefinition> tools, int maxTurns,
                     List<ToolHook> hooks) {
        this(llm, model, systemPrompt, tools, maxTurns, hooks, null, true);
    }

    /**
     * 带钩子与中断触发器的构造。默认启用流式。
     */
    public AgentLoop(LlmClient llm, Model model, String systemPrompt, List<ToolDefinition> tools, int maxTurns,
                     List<ToolHook> hooks, Supplier<InterruptTrigger> triggerSupplier) {
        this(llm, model, systemPrompt, tools, maxTurns, hooks, triggerSupplier, true);
    }

    /**
     * 全参构造：钩子链 + 中断触发器 + 流式开关。所有构造器最终委派至此，hooks 为 null 即空列表。
     *
     * @param hooks            工具钩子链（null → 空列表，行为与 MVP1 一致）
     * @param triggerSupplier  中断触发器供应方（null → 永不取消）
     * @param streamingEnabled false 时禁用流式，streamOnce 直接委派 chat
     */
    public AgentLoop(LlmClient llm, Model model, String systemPrompt, List<ToolDefinition> tools, int maxTurns,
                     List<ToolHook> hooks, Supplier<InterruptTrigger> triggerSupplier, boolean streamingEnabled) {
        this.llm = llm;
        this.model = model;
        this.systemPrompt = systemPrompt;
        this.tools = tools;
        this.maxTurns = maxTurns;
        this.hooks = hooks != null ? hooks : List.of();
        this.triggerSupplier = triggerSupplier != null ? triggerSupplier : () -> () -> false;
        this.streamingEnabled = streamingEnabled;
    }

    /**
     * 执行 Agent 循环（无历史）
     */
    public List<Message> run(List<Message> initialPrompts, EventSink sink) throws Exception {
        return runWithHistory(List.of(), initialPrompts, sink);
    }

    /**
     * 执行 Agent 循环（带历史，用于 REPL 多轮对话）
     *
     * @param history    历史消息（已完成的对话）
     * @param newPrompts 本轮新提示
     * @param sink       事件接收器
     * @return 本轮产生的新消息（包含 newPrompts + 助手回复 + 工具结果）
     */
    public List<Message> runWithHistory(List<Message> history, List<Message> newPrompts, EventSink sink) throws Exception {
        List<Message> contextMessages = new ArrayList<>(history);
        contextMessages.addAll(newPrompts);
        List<Message> newMessages = new ArrayList<>(newPrompts);

        if (sink != null) sink.on(new AgentEvent.AgentStart());
        int turn = 0;
        while (turn < maxTurns) {
            turn++;
            if (sink != null) sink.on(new AgentEvent.TurnStart(turn));

            Context ctx = new Context(systemPrompt, List.copyOf(contextMessages),
                    tools.stream().map(ToolDefinition::toLlmTool).collect(Collectors.toList()));

            // 每回合获取一次触发器：stream 与工具执行段共用，finally 统一释放（与「每次 stream 一取一放」的
            // 关闭次数语义一致，triggerCloseIsCalledAfterStream 断言 1 次/回合）；管道模式不碰 supplier。
            InterruptTrigger turnTrigger = streamingEnabled ? acquireTrigger() : () -> false;
            try {
                Message assistant = streamOnce(ctx, sink, turnTrigger);

                // 归一化空值
                if (assistant.content == null) assistant.content = List.of();
                if (assistant.stopReason == null) assistant.stopReason = "end";

                contextMessages.add(assistant);
                newMessages.add(assistant);
                if (sink != null) sink.on(new AgentEvent.MessageEnd(assistant));

                // 遇到错误/中断直接结束——aborted 时本轮尚未执行的工具调用不执行，循环正常收尾（下一轮可继续）
                if ("error".equals(assistant.stopReason) || "aborted".equals(assistant.stopReason)) {
                    if (sink != null) sink.on(new AgentEvent.TurnEnd(assistant, List.of()));
                    if (sink != null) sink.on(new AgentEvent.AgentEnd(newMessages));
                    return newMessages;
                }

                List<Message.ToolCall> toolCalls = assistant.toolCalls();
                if (toolCalls.isEmpty()) {
                    // 无工具调用，说明任务已完成
                    if (sink != null) sink.on(new AgentEvent.TurnEnd(assistant, List.of()));
                    if (sink != null) sink.on(new AgentEvent.AgentEnd(newMessages));
                    return newMessages;
                }

                // 被截断（length）：全部工具调用视为失败，对齐 pi 的处理（不走钩子、不走并行）
                if ("length".equals(assistant.stopReason)) {
                    List<Message> results = new ArrayList<>();
                    for (Message.ToolCall tc : toolCalls) {
                        String err = "工具调用因 token 超限被截断（stopReason=length），参数可能不完整。";
                        Message r = Message.toolResult(tc.id, err, true);
                        results.add(r);
                        contextMessages.add(r);
                        newMessages.add(r);
                        if (sink != null) sink.on(new AgentEvent.ToolResultEvent(tc, err, true));
                    }
                    if (sink != null) sink.on(new AgentEvent.TurnEnd(assistant, results));
                    continue; // 让模型看到错误后重试
                }

                // 按 LLM 发出顺序切连续段（runs）：全 READ_ONLY 段并行、含 STATEFUL 段串行，段间保持先后；
                // 每工具仍走票 01 的同步钩子链（per-tool 独立票决），结果严格按发出顺序回 LLM（docs/adr/0005）
                List<Message> toolResults = new ArrayList<>();
                Message[] orderedResults = new Message[toolCalls.size()];
                int start = 0;
                while (start < orderedResults.length) {
                    // 回合级取消检查：每段开始前，置位则剩余工具构造取消结果、不再执行
                    if (turnTrigger.isCancelled()) {
                        fillCancelled(orderedResults, toolCalls, start, sink, ABORTED_OUTPUT);
                        break;
                    }
                    int end = start + 1;
                    if (isReadOnly(toolCalls.get(start))) {
                        while (end < orderedResults.length && isReadOnly(toolCalls.get(end))) end++;
                    }
                    if (end - start > 1) {
                        executeGroupParallel(toolCalls.subList(start, end), orderedResults, start, turnTrigger, sink);
                    } else {
                        // 单工具（含单工具回合）走原串行路径，完全不经过线程池
                        orderedResults[start] = finishToolOutcome(runToolCall(toolCalls.get(start), sink), sink);
                    }
                    start = end;
                }
                for (Message r : orderedResults) {
                    toolResults.add(r);
                    contextMessages.add(r);
                    newMessages.add(r);
                }

                if (sink != null) sink.on(new AgentEvent.TurnEnd(assistant, toolResults));
                // 带上工具结果进入下一轮
            } finally {
                closeQuietly(turnTrigger);
            }
        }

        if (sink != null) sink.on(new AgentEvent.AgentEnd(newMessages));
        return newMessages;
    }

    /**
     * 单个工具调用的完整执行链（钩子 + execute + 事件），行为语义与票 01 串行路径逐分支一致：
     * 未知工具短路（不走钩子、不发事件）；beforeToolCall 链 BLOCK / MODIFY / 异常三态；
     * execute 抛异常 → 构造 isError「工具执行失败」结果并标记 executeFailure（并行组据此 fail-fast，
     * 串行路径该标记不改变行为，结果照常回 LLM）。
     * <p>
     * 不碰 contextMessages/newMessages——返回结果由主线程按 LLM 顺序落账；并行组内本方法运行在
     * 工作线程上，钩子与 sink 的并发处理见类头 javadoc。
     * </p>
     */
    private ToolOutcome runToolCall(Message.ToolCall tc, EventSink sink) {
        ToolDefinition def = findTool(tc.name);
        if (def == null) {
            // 未知工具不走钩子、不发 ToolStart/ToolResultEvent（与 MVP1 一致），仅回 isError 结果
            return new ToolOutcome(tc, "未知工具: " + tc.name, true, false, false, false);
        }

        // beforeToolCall 链：按序票决，BLOCK 短路后续钩子、MODIFY 替换调用后续钩子看到修改后的、异常短路为 hook failed
        Message.ToolCall currentCall = tc;
        boolean blocked = false;
        String blockReason = null;
        String hookError = null;
        for (ToolHook hook : hooks) {
            Map<String, Object> effArgs = currentCall.arguments != null ? currentCall.arguments : Map.of();
            ToolDecision decision;
            try {
                decision = hook.beforeToolCall(new ToolCallEvent(currentCall, effArgs));
            } catch (Exception e) {
                hookError = e.getMessage();
                log.warn("钩子 beforeToolCall 执行失败", e);
                break;
            }
            if (decision == null || decision.action() == ToolDecision.Action.PROCEED) continue;
            if (decision.action() == ToolDecision.Action.BLOCK) {
                blocked = true;
                blockReason = decision.reason();
                break;
            }
            // MODIFY：替换当前调用，继续走后续钩子
            if (decision.modifiedCall() != null) currentCall = decision.modifiedCall();
        }

        if (blocked) {
            return new ToolOutcome(currentCall, "blocked by hook: " + (blockReason != null ? blockReason : ""),
                    true, true, true, false);
        }
        if (hookError != null) {
            // 钩子异常：工具未执行、不发 ToolStart、不走 afterToolCall，仅回 isError 结果（不算异常、不触发 fail-fast）
            return new ToolOutcome(currentCall, "hook failed: " + hookError, true, false, true, false);
        }

        emit(sink, new AgentEvent.ToolStart(currentCall));
        String output;
        boolean isError;
        boolean executeFailure = false;
        try {
            Map<String, Object> args = currentCall.arguments != null ? currentCall.arguments : Map.of();
            ToolResult r = def.execute(currentCall.id, args);
            output = r.content();
            isError = r.isError();
        } catch (Exception e) {
            output = "工具执行失败: " + e.getMessage();
            isError = true;
            executeFailure = true; // 唯一触发并行组 fail-fast 的异常来源；钩子失败与 isError 结果均不算
            log.warn("工具 {} 执行失败", currentCall.name, e);
        }
        return new ToolOutcome(currentCall, output, isError, true, true, executeFailure);
    }

    /**
     * 收尾一个工具决定：按票 01 语义先走 afterToolCall 观测钩子（异常仅记日志），再发射 ToolResultEvent，
     * 最后构建回 LLM 的工具结果消息。被取消/未知的合成结果经标志位跳过相应步骤。
     */
    private Message finishToolOutcome(ToolOutcome o, EventSink sink) {
        AgentEvent.ToolResultEvent ev = new AgentEvent.ToolResultEvent(o.call(), o.output(), o.isError());
        if (o.runAfterHooks()) {
            for (ToolHook hook : hooks) {
                try {
                    hook.afterToolCall(ev);
                } catch (Exception e) {
                    log.warn("钩子 afterToolCall 执行失败", e);
                }
            }
        }
        if (o.emitResult()) emit(sink, ev);
        return Message.toolResult(o.call().id, o.output(), o.isError());
    }

    /**
     * 并行执行一个全 READ_ONLY 连续段：全部提交 daemon 线程池，按 LLM 发出顺序回收结果。
     * fail-fast：任一任务 execute 抛异常（或任务级异常）→ {@code Future.cancel(true)} 取消同组兄弟，
     * 被取消者构造 isError「cancelled」结果、已完成结果保留，整轮不中断（LLM 下一轮看到错误集）。
     * 钩子的 BLOCK / hook-failed / 工具 isError 结果均非异常，不触发 fail-fast，兄弟照跑（per-tool 独立票决）。
     * 中止：收集循环每一轮检查回合触发器，置位则同样 cancel 组内未完成者。
     */
    private void executeGroupParallel(List<Message.ToolCall> group, Message[] orderedResults, int base,
                                      InterruptTrigger trigger, EventSink sink) {
        ExecutorService ex = ensureToolExecutor();
        List<Future<ToolOutcome>> futures = new ArrayList<>(group.size());
        for (Message.ToolCall tc : group) {
            futures.add(ex.submit(() -> runToolCall(tc, sink)));
        }
        boolean failed = false;
        for (int i = 0; i < group.size(); i++) {
            Future<ToolOutcome> f = futures.get(i);
            if (!failed && trigger.isCancelled()) failed = true; // 已提交的组遇中止：取消未完成者
            ToolOutcome out;
            if (failed) {
                // cancel 返回 false = 已完成：保留其结果；未启动/在途被取消：构造 cancelled 结果
                if (f.cancel(true)) out = cancelledOutcome(group.get(i));
                else out = completedOutcome(f, group.get(i));
            } else {
                try {
                    out = f.get();
                } catch (CancellationException ce) {
                    out = cancelledOutcome(group.get(i));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    failed = true;
                    out = cancelledOutcome(group.get(i));
                } catch (ExecutionException ee) {
                    // runToolCall 已兜住工具异常，走到这里只剩任务级意外：按 fail-fast 处理
                    Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
                    log.warn("并行工具任务异常", cause);
                    out = new ToolOutcome(group.get(i), "工具执行失败: " + cause.getMessage(), true, false, true, true);
                }
                if (out != null && out.executeFailure()) failed = true;
            }
            orderedResults[base + i] = finishToolOutcome(out, sink);
        }
    }

    private static ToolOutcome cancelledOutcome(Message.ToolCall tc) {
        return new ToolOutcome(tc, CANCELLED_OUTPUT, true, false, true, false);
    }

    /** 取消已来不及（任务已完成）：取其完成结果保留；取不到再降级为 cancelled。 */
    private static ToolOutcome completedOutcome(Future<ToolOutcome> f, Message.ToolCall tc) {
        try {
            return f.get();
        } catch (Exception e) {
            return cancelledOutcome(tc);
        }
    }

    /** 中止/异常收尾：从 from 起把未执行工具全部构造 cancelled 结果（发事件、不走钩子——工具从未执行）。 */
    private void fillCancelled(Message[] orderedResults, List<Message.ToolCall> toolCalls, int from,
                               EventSink sink, String text) {
        for (int i = from; i < orderedResults.length; i++) {
            Message.ToolCall tc = toolCalls.get(i);
            orderedResults[i] = finishToolOutcome(new ToolOutcome(tc, text, true, false, true, false), sink);
        }
    }

    /** 工具是否可并行（READ_ONLY）；未知工具按 STATEFUL 处理（fail-safe，保持串行短路）。 */
    private boolean isReadOnly(Message.ToolCall tc) {
        ToolDefinition def = findTool(tc.name);
        return def != null && def.kind() == ToolDefinition.ToolKind.READ_ONLY;
    }

    /** 懒创建 daemon 固定线程池（仅并行段用到；线程命名 mini-code-tool-N）。双检锁，池引用 volatile。 */
    private ExecutorService ensureToolExecutor() {
        ExecutorService ex = toolExecutor;
        if (ex == null) {
            synchronized (this) {
                ex = toolExecutor;
                if (ex == null) {
                    ex = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors(), r -> {
                        Thread t = new Thread(r, "mini-code-tool-" + toolThreadSeq.incrementAndGet());
                        t.setDaemon(true);
                        return t;
                    });
                    toolExecutor = ex;
                }
            }
        }
        return ex;
    }

    /** 获取回合触发器：supplier 异常或返回 null 按永不取消处理（与 streamOnce 容错语义一致）。 */
    private InterruptTrigger acquireTrigger() {
        InterruptTrigger t = null;
        try {
            t = triggerSupplier.get();
        } catch (Exception e) {
            log.warn("获取中断触发器失败，按永不取消处理", e);
        }
        return t != null ? t : () -> false;
    }

    private static void closeQuietly(AutoCloseable c) {
        try {
            c.close();
        } catch (Exception ignore) {
        }
    }

    /** 线程安全事件发射：并行工具下 sink 回调可交错但绝不被并发调用（sink 实现无并发义务）。 */
    private void emit(EventSink sink, AgentEvent e) {
        if (sink == null) return;
        synchronized (eventLock) {
            sink.on(e);
        }
    }

    /**
     * 单次调用：使用调用方按回合获取的中断触发器（置位即取消），本方法不获取、不释放；
     * 每个文本片段回调即发射 {@link AgentEvent.MessageUpdate}，累积为完整 Message 后返回。
     * 管道模式禁用流式时直接走同步 chat 等价路径，零流式事件发射、无逐字输出（不读触发器）。
     * 中断语义：已收文本以 stopReason=aborted 的 partial 进入历史，本轮工具不执行。
     *
     * @param trigger 本回合的中断触发器（由 runWithHistory 获取、finally 释放；管道模式为永不取消桩）
     */
    private Message streamOnce(Context ctx, EventSink sink, InterruptTrigger trigger) throws Exception {
        // 管道禁用流式：走既有同步路径，等价 chat，不发射 MessageUpdate
        if (!streamingEnabled) {
            Message sync = llm.chat(model, ctx);
            if (sync == null) {
                sync = buildAborted("");
                sync.stopReason = "error";
            }
            return sync;
        }
        Supplier<Boolean> isCancelled = trigger != null ? trigger::isCancelled : () -> false;

        StringBuilder partialBuffer = new StringBuilder();
        Message result;
        try {
            if (sink != null) sink.on(new AgentEvent.MessageStart());   // 消息生命周期起点（对齐 pi message_start）
            result = llm.stream(model, ctx, delta -> {
                if (delta != null) {
                    partialBuffer.append(delta);
                    if (!delta.isEmpty() && sink != null) {
                        sink.on(new AgentEvent.MessageUpdate(delta));
                    }
                }
            }, isCancelled);
        } catch (CancellationException ce) {
            if (actuallyCancelled(isCancelled)) {
                Thread.currentThread().interrupt();
                result = buildAborted(partialBuffer.toString());
                log.debug("流式已取消，返回 partial aborted，长度 {}", partialBuffer.length());
            } else {
                log.warn("非取消上下文的 CancellationException，不转为 aborted", ce);
                throw ce;
            }
        } catch (InterruptedException ie) {
            if (actuallyCancelled(isCancelled)) {
                Thread.currentThread().interrupt();
                result = buildAborted(partialBuffer.toString());
                log.debug("流式被中断，返回 partial aborted");
            } else {
                log.warn("非取消上下文的 InterruptedException，不转为 aborted", ie);
                Thread.currentThread().interrupt();
                throw ie;
            }
        } catch (Exception e) {
            if (actuallyCancelled(isCancelled)) {
                result = buildAborted(partialBuffer.toString());
                log.debug("流式异常但已置取消，返回 partial aborted: {}", e.toString());
            } else {
                throw e;
            }
        }

        // 若流式返回的 aborted 但文本为空，回退使用 partialBuffer（保证已收片段不丢）
        if (result != null && "aborted".equals(result.stopReason)) {
            String text = result.text();
            if ((text == null || text.isEmpty()) && partialBuffer.length() > 0) {
                result = buildAborted(partialBuffer.toString());
            }
        }
        return result;
    }

    private static Message buildAborted(String partialText) {
        Message m = new Message();
        m.role = Message.Role.assistant;
        m.content = List.of(Message.Content.text(partialText != null ? partialText : ""));
        m.stopReason = "aborted";
        return m;
    }

    /** 判定当前流式是否「真取消」：中断触发器置位或本线程已被中断。异常上下文里读 isCancelled 可能抛异常，吞掉返回 false。 */
    private boolean actuallyCancelled(Supplier<Boolean> isCancelled) {
        try {
            return isCancelled.get() || Thread.currentThread().isInterrupted();
        } catch (Exception ignore) {
            return false;
        }
    }

    private ToolDefinition findTool(String name) {
        for (ToolDefinition t : tools) if (t.name().equals(name)) return t;
        return null;
    }

    /**
     * 单个工具调用的执行决定（内部值对象）——runToolCall 的产物，由主线程按 LLM 顺序收尾落账。
     *
     * @param call           生效调用（经 MODIFY 钩子后即替换版本）
     * @param output         回 LLM 的文本
     * @param isError        是否失败
     * @param runAfterHooks  是否走 afterToolCall 观测钩子（未知工具 / hook-failed 不走，票 01 语义）
     * @param emitResult     是否发射 ToolResultEvent（未知工具不发，与 MVP1 一致）
     * @param executeFailure 是否 execute 抛异常所致——并行组 fail-fast 的唯一触发条件（钩子 BLOCK、
     *                       hook-failed 与工具返回的 isError 结果均不算异常）
     */
    private record ToolOutcome(Message.ToolCall call, String output, boolean isError,
                               boolean runAfterHooks, boolean emitResult, boolean executeFailure) {
    }

    /**
     * 事件回调
     */
    public interface EventSink {
        void on(AgentEvent e);
    }
}
