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
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 最小 Agent 循环，忠实对齐 pi 的 packages/agent/src/agent-loop.ts。
 * 简化版：单层循环，不含 steering/followUp，工具顺序执行。
 * 通过 EventSink 对外发射事件，便于观测与后续 TUI 接入。
 * <p>
 * 03 票起统一走 {@link LlmClient#stream} 流式路径：每个文本片段回调即发射
 * {@link AgentEvent.StreamDelta}，片段累积为完整文本后走既有 MessageEnd 路径；
 * 工具调用分发的完整 JSON 仍从拼装结果取。default stream 在零回调时等价同步，
 * 因此既有 fake 测试零改动；中断触发器可注入，置位即通过 stream 的取消参数生效。
 * </p>
 */
public class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);
    private final LlmClient llm;
    private final Model model;
    private final String systemPrompt;
    private final List<ToolDefinition> tools;
    private final int maxTurns; // 最大轮次，防止无限循环
    private final Supplier<InterruptTrigger> triggerSupplier;
    private final boolean streamingEnabled; // 管道模式禁用流式，零 StreamDelta 发射

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
     * 带流式开关的构造——管道模式（非 tty）传 false 走同步 chat 等价路径，零 StreamDelta 发射。
     *
     * @param streamingEnabled false 时禁用流式，streamOnce 直接委派 chat，不发射 StreamDelta
     */
    public AgentLoop(LlmClient llm, Model model, String systemPrompt, List<ToolDefinition> tools, int maxTurns,
                     Supplier<InterruptTrigger> triggerSupplier, boolean streamingEnabled) {
        this.llm = llm;
        this.model = model;
        this.systemPrompt = systemPrompt;
        this.tools = tools;
        this.maxTurns = maxTurns;
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

            Message assistant = streamOnce(ctx, sink);

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

            // 被截断（length）：全部工具调用视为失败，对齐 pi 的处理
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

            // 顺序执行工具（MVP），MVP2 再加并行
            List<Message> toolResults = new ArrayList<>();
            for (Message.ToolCall tc : toolCalls) {
                ToolDefinition def = findTool(tc.name);
                String output;
                boolean isError;
                if (def == null) {
                    output = "未知工具: " + tc.name;
                    isError = true;
                } else {
                    if (sink != null) sink.on(new AgentEvent.ToolStart(tc));
                    try {
                        Map<String, Object> args = tc.arguments != null ? tc.arguments : Map.of();
                        ToolResult r = def.execute(tc.id, args);
                        output = r.content();
                        isError = r.isError();
                    } catch (Exception e) {
                        output = "工具执行失败: " + e.getMessage();
                        isError = true;
                        log.warn("工具 {} 执行失败", tc.name, e);
                    }
                    if (sink != null) sink.on(new AgentEvent.ToolResultEvent(tc, output, isError));
                }
                Message resultMsg = Message.toolResult(tc.id, output, isError);
                toolResults.add(resultMsg);
                contextMessages.add(resultMsg);
                newMessages.add(resultMsg);
            }

            if (sink != null) sink.on(new AgentEvent.TurnEnd(assistant, toolResults));
            // 带上工具结果进入下一轮
        }

        if (sink != null) sink.on(new AgentEvent.AgentEnd(newMessages));
        return newMessages;
    }

    /**
     * 单次调用：流式启用时每次取触发器（置位即取消），完成后释放；
     * 每个文本片段回调即发射 {@link AgentEvent.StreamDelta}，累积为完整 Message 后返回。
     * 管道模式禁用流式时直接走同步 chat 等价路径，零 StreamDelta 发射、无逐字输出。
     * 中断语义：已收文本以 stopReason=aborted 的 partial 进入历史，本轮工具不执行。
     */
    private Message streamOnce(Context ctx, EventSink sink) throws Exception {
        // 管道禁用流式：走既有同步路径，等价 chat，不发射 StreamDelta
        if (!streamingEnabled) {
            Message sync = llm.chat(model, ctx);
            if (sync == null) {
                sync = buildAborted("");
                sync.stopReason = "error";
            }
            return sync;
        }
        InterruptTrigger trigger = null;
        Supplier<Boolean> isCancelled = () -> false;
        if (triggerSupplier != null) {
            try {
                trigger = triggerSupplier.get();
            } catch (Exception e) {
                log.warn("获取中断触发器失败，按永不取消处理", e);
                trigger = null;
            }
            if (trigger != null) {
                isCancelled = trigger::isCancelled;
            }
        }

        StringBuilder partialBuffer = new StringBuilder();
        Message result;
        try {
            result = llm.stream(model, ctx, delta -> {
                if (delta != null) {
                    partialBuffer.append(delta);
                    if (!delta.isEmpty() && sink != null) {
                        sink.on(new AgentEvent.StreamDelta(delta));
                    }
                }
            }, isCancelled);
        } catch (CancellationException ce) {
            boolean actuallyCancelled = false;
            try { actuallyCancelled = isCancelled.get() || Thread.currentThread().isInterrupted(); } catch (Exception ignore) {}
            if (actuallyCancelled) {
                Thread.currentThread().interrupt();
                result = buildAborted(partialBuffer.toString());
                log.debug("流式已取消，返回 partial aborted，长度 {}", partialBuffer.length());
            } else {
                log.warn("非取消上下文的 CancellationException，不转为 aborted", ce);
                throw ce;
            }
        } catch (InterruptedException ie) {
            boolean actuallyCancelled = false;
            try { actuallyCancelled = isCancelled.get() || Thread.currentThread().isInterrupted(); } catch (Exception ignore) {}
            if (actuallyCancelled) {
                Thread.currentThread().interrupt();
                result = buildAborted(partialBuffer.toString());
                log.debug("流式被中断，返回 partial aborted");
            } else {
                log.warn("非取消上下文的 InterruptedException，不转为 aborted", ie);
                Thread.currentThread().interrupt();
                throw ie;
            }
        } catch (Exception e) {
            boolean cancelled = false;
            try {
                cancelled = isCancelled.get() || Thread.currentThread().isInterrupted();
            } catch (Exception ignore) {
            }
            if (cancelled) {
                result = buildAborted(partialBuffer.toString());
                log.debug("流式异常但已置取消，返回 partial aborted: {}", e.toString());
            } else {
                throw e;
            }
        } finally {
            if (trigger != null) {
                try {
                    trigger.close();
                } catch (Exception ignore) {
                }
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

    private ToolDefinition findTool(String name) {
        for (ToolDefinition t : tools) if (t.name().equals(name)) return t;
        return null;
    }

    /**
     * 事件回调
     */
    public interface EventSink {
        void on(AgentEvent e);
    }
}
