package dev.minicode.agent;

import dev.minicode.ai.Context;
import dev.minicode.ai.LlmClient;
import dev.minicode.ai.Message;
import dev.minicode.ai.Model;
import dev.minicode.tools.BashTool;
import dev.minicode.tools.ToolDefinition;
import dev.minicode.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ticket 02：工具并行执行验收。
 * 主缝 = AgentLoop + fake LlmClient（lambda + AtomicInteger 回合计数，参考 {@link ToolHooksTest}）。
 * 并行性证明用 CyclicBarrier 互等（超时 5s，只有真并发双方才都通过）；串行用进入/退出顺序记录断言；
 * 不写 timing 断言。fail-fast / BashTool 中断 / 钩子在并行路径下 per-tool 独立票决均在此覆盖。
 */
class AgentParallelExecutionTest {

    /** 测试工具：可配 kind、可注入 execute 行为，记录进入/退出顺序与执行线程名（并发安全）。 */
    static class ProbeTool implements ToolDefinition {
        final String toolName;
        final ToolKind toolKind;
        final Function<String, ToolResult> body;
        final List<String> log;
        final AtomicReference<String> execThreadName = new AtomicReference<>();

        ProbeTool(String toolName, ToolKind toolKind, List<String> log, Function<String, ToolResult> body) {
            this.toolName = toolName;
            this.toolKind = toolKind;
            this.body = body;
            this.log = log;
        }

        @Override public String name() { return toolName; }
        @Override public String description() { return toolName; }
        @Override public JsonNode parameters() { return new ObjectMapper().createObjectNode(); }
        @Override public ToolKind kind() { return toolKind; }

        @Override
        public ToolResult execute(String callId, Map<String, Object> args) throws Exception {
            execThreadName.set(Thread.currentThread().getName());
            log.add("enter:" + toolName);
            try {
                return body.apply(callId);
            } finally {
                log.add("exit:" + toolName);
            }
        }
    }

    private static ToolResult okRan(String callId) { return ToolResult.ok("ran:" + callId); }

    private static final Model MODEL = Model.opencodeGo("k");

    /** 第 0 回合按序发多个工具调用，其余纯文本结束 */
    private static LlmClient fakeTurns(List<Message.ToolCall> calls, List<Context> seen) {
        AtomicInteger c = new AtomicInteger(0);
        return (model, ctx) -> {
            if (seen != null) seen.add(ctx);
            int n = c.getAndIncrement();
            if (n == 0) {
                List<Message.Content> cs = new ArrayList<>();
                for (Message.ToolCall tc : calls) cs.add(Message.Content.toolCall(tc));
                return assistant(cs, "toolCalls");
            }
            return end("done");
        };
    }

    private static Message.ToolCall tc(String id, String name) {
        return new Message.ToolCall(id, name, Map.of(), "{}");
    }

    // ===== kind() 默认与 ReadTool =====
    @Test
    void readToolIsReadOnlyOthersDefaultStateful(@TempDir Path tmp) {
        assertEquals(ToolDefinition.ToolKind.READ_ONLY, new dev.minicode.tools.ReadTool(tmp).kind());
        assertEquals(ToolDefinition.ToolKind.STATEFUL, new dev.minicode.tools.WriteTool(tmp).kind());
        assertEquals(ToolDefinition.ToolKind.STATEFUL, new dev.minicode.tools.EditTool(tmp).kind());
        assertEquals(ToolDefinition.ToolKind.STATEFUL, new BashTool(tmp).kind());
    }

    // ===== 同 turn 双 READ_ONLY：CyclicBarrier 互等证明真并发 =====
    @Test
    void twoReadOnlyToolsRunConcurrently() throws Exception {
        List<String> log = new CopyOnWriteArrayList<>();
        CyclicBarrier bothArrived = new CyclicBarrier(2);
        AtomicBoolean overlap1 = new AtomicBoolean();
        AtomicBoolean overlap2 = new AtomicBoolean();

        ProbeTool r1 = new ProbeTool("r1", ToolDefinition.ToolKind.READ_ONLY, log, id -> {
            try { bothArrived.await(5, TimeUnit.SECONDS); overlap1.set(true); }
            catch (Exception e) { overlap1.set(false); }
            return okRan(id);
        });
        ProbeTool r2 = new ProbeTool("r2", ToolDefinition.ToolKind.READ_ONLY, log, id -> {
            try { bothArrived.await(5, TimeUnit.SECONDS); overlap2.set(true); }
            catch (Exception e) { overlap2.set(false); }
            return okRan(id);
        });

        AgentLoop loop = new AgentLoop(fakeTurns(List.of(tc("a", "r1"), tc("b", "r2")), null),
                MODEL, "sys", List.of(r1, r2), 5);
        List<Message> out = loop.run(List.of(Message.user("hi")), null);

        assertTrue(overlap1.get() && overlap2.get(),
                "两个 READ_ONLY 工具应在 barrier 处互相等到（真并发）；log=" + log);
        // 串行则必有一个超时（barrier 永不凑齐），overlap 为 false
        assertTrue(out.stream().anyMatch(m -> m.role == Message.Role.toolResult && m.text().contains("ran:a")));
        assertTrue(out.stream().anyMatch(m -> m.role == Message.Role.toolResult && m.text().contains("ran:b")));
    }

    // ===== 单工具回合不经过池：execute 线程仍是 main =====
    @Test
    void singleToolTurnRunsOnCallerThread() throws Exception {
        List<String> log = new CopyOnWriteArrayList<>();
        ProbeTool r1 = new ProbeTool("r1", ToolDefinition.ToolKind.READ_ONLY, log, AgentParallelExecutionTest::okRan);
        AgentLoop loop = new AgentLoop(fakeTurns(List.of(tc("a", "r1")), null),
                MODEL, "sys", List.of(r1), 5);
        loop.run(List.of(Message.user("hi")), null);
        assertEquals("main", r1.execThreadName.get(), "单工具回合应走原串行路径（main 线程），完全不建池");
    }

    // ===== 并行组走 mini-code-tool-* 命名线程 =====
    @Test
    void parallelGroupUsesNamedToolThreads() throws Exception {
        List<String> log = new CopyOnWriteArrayList<>();
        CyclicBarrier bothArrived = new CyclicBarrier(2);
        ProbeTool r1 = new ProbeTool("r1", ToolDefinition.ToolKind.READ_ONLY, log, id -> {
            awaitQuiet(bothArrived); return okRan(id);
        });
        ProbeTool r2 = new ProbeTool("r2", ToolDefinition.ToolKind.READ_ONLY, log, id -> {
            awaitQuiet(bothArrived); return okRan(id);
        });
        AgentLoop loop = new AgentLoop(fakeTurns(List.of(tc("a", "r1"), tc("b", "r2")), null),
                MODEL, "sys", List.of(r1, r2), 5);
        loop.run(List.of(Message.user("hi")), null);
        assertTrue(r1.execThreadName.get() != null && r1.execThreadName.get().startsWith("mini-code-tool-"),
                "并行组工具线程应命名 mini-code-tool-N，实际=" + r1.execThreadName.get());
        assertTrue(r2.execThreadName.get() != null && r2.execThreadName.get().startsWith("mini-code-tool-"),
                "并行组工具线程应命名 mini-code-tool-N，实际=" + r2.execThreadName.get());
    }

    private static void awaitQuiet(CyclicBarrier b) {
        try { b.await(5, TimeUnit.SECONDS); } catch (Exception ignore) { }
    }

    // ===== 双 STATEFUL：进入/退出顺序记录断言串行（不经过池） =====
    @Test
    void twoStatefulToolsRunSeriallyInOrder() throws Exception {
        List<String> log = new CopyOnWriteArrayList<>();
        ProbeTool w1 = new ProbeTool("w1", ToolDefinition.ToolKind.STATEFUL, log, id -> { nap(60); return okRan(id); });
        ProbeTool w2 = new ProbeTool("w2", ToolDefinition.ToolKind.STATEFUL, log, id -> { nap(60); return okRan(id); });

        AgentLoop loop = new AgentLoop(fakeTurns(List.of(tc("a", "w1"), tc("b", "w2")), null),
                MODEL, "sys", List.of(w1, w2), 5);
        loop.run(List.of(Message.user("hi")), null);

        assertEquals(List.of("enter:w1", "exit:w1", "enter:w2", "exit:w2"), log,
                "STATEFUL 段必须按发出顺序串行，无交错（不写 timing 断言）");
        assertEquals("main", w1.execThreadName.get(), "STATEFUL 走串行路径，不建池、不交线程池");
        assertEquals("main", w2.execThreadName.get());
    }

    // ===== 混合 run [read1, read2, write1, read3]：段间先后 + 结果按 LLM 顺序回收 =====
    @Test
    void mixedRunsKeepSegmentOrderAndLlmResultOrder() throws Exception {
        List<String> log = new CopyOnWriteArrayList<>();
        CyclicBarrier bothArrived = new CyclicBarrier(2);
        List<Context> seen = new ArrayList<>();

        ProbeTool r1 = new ProbeTool("r1", ToolDefinition.ToolKind.READ_ONLY, log, id -> {
            awaitQuiet(bothArrived); return okRan(id);
        });
        ProbeTool r2 = new ProbeTool("r2", ToolDefinition.ToolKind.READ_ONLY, log, id -> {
            awaitQuiet(bothArrived); return okRan(id);
        });
        ProbeTool w1 = new ProbeTool("w1", ToolDefinition.ToolKind.STATEFUL, log, AgentParallelExecutionTest::okRan);
        ProbeTool r3 = new ProbeTool("r3", ToolDefinition.ToolKind.READ_ONLY, log, AgentParallelExecutionTest::okRan);

        AgentLoop loop = new AgentLoop(
                fakeTurns(List.of(tc("a", "r1"), tc("b", "r2"), tc("c", "w1"), tc("d", "r3")), seen),
                MODEL, "sys", List.of(r1, r2, w1, r3), 5);
        loop.run(List.of(Message.user("hi")), null);

        // write1 必须在两个 read（r1/r2）退出之后、r3 进入之前执行
        int enterW = log.indexOf("enter:w1");
        int exitW = log.indexOf("exit:w1");
        int enterR3 = log.indexOf("enter:r3");
        assertTrue(enterW > 0 && exitW > enterW && enterR3 > exitW,
                "段间先后应成立：r1&r2 段 → w1 → r3；实际 log=" + log);
        assertTrue(log.containsAll(List.of("exit:r1", "exit:r2")) && log.indexOf("exit:r1") < enterW
                        && log.indexOf("exit:r2") < enterW,
                "write 必须在 r1、r2 均退出后执行（段间保持先后）；log=" + log);

        // 回 LLM 的结果严格按发出顺序 a,b,c,d
        List<Message> toolResults = seen.get(1).messages.stream()
                .filter(m -> m.role == Message.Role.toolResult).toList();
        List<String> ids = toolResults.stream()
                .map(m -> m.content.get(0).toolCallId).toList();
        assertEquals(List.of("a", "b", "c", "d"), ids, "结果必须按 LLM 发出顺序回收");
    }

    // ===== fail-fast：组内一个抛异常 → 兄弟被取消 → isError「cancelled」、循环继续 =====
    // 兄弟工具被 cancel(true) 线程中断：若中断落入 execute 前尚未返回（cancel 胜出）→ 构造 cancelled 结果；
    // 若兄弟恰好先带着中断返回异常（cancel 落空）→ 保留其失败结果。两种竞态都满足验收
    // 「被取消的兄弟 isError「cancelled」结果」，这里只断言 isError 且含 cancelled 语义。
    @Test
    void failFastCancelsSiblingsButKeepsCompleted() throws Exception {
        List<String> log = new CopyOnWriteArrayList<>();
        List<Context> seen = new ArrayList<>();

        ProbeTool bad = new ProbeTool("bad", ToolDefinition.ToolKind.READ_ONLY, log, id -> {
            throw new RuntimeException("boom");
        });
        // 兄弟：轮询中断位，被 cancel(true) 中断后以「cancelled」失败退出（不以长 sleep 阻塞——取消竞态两端都可断言）
        ProbeTool slow = new ProbeTool("slow", ToolDefinition.ToolKind.READ_ONLY, log, id -> {
            while (!Thread.currentThread().isInterrupted()) nap(50);
            throw new RuntimeException("sibling-cancelled-by-interrupt");
        });

        AgentLoop loop = new AgentLoop(fakeTurns(List.of(tc("a", "bad"), tc("b", "slow")), seen),
                MODEL, "sys", List.of(bad, slow), 5);
        loop.run(List.of(Message.user("hi")), null);

        // LLM 下一轮收到全组结果：bad 失败结果 + slow isError「cancelled」结果（整轮不中断）
        List<Message> results = seen.get(1).messages.stream()
                .filter(m -> m.role == Message.Role.toolResult).toList();
        assertEquals(2, results.size(), "整组结果都应回 LLM");
        assertTrue(results.stream().anyMatch(m -> m.content.get(0).isError
                && m.text().contains("工具执行失败") && m.text().contains("boom")), "bad 工具应回失败结果");
        assertTrue(results.stream().anyMatch(m -> m.content.get(0).isError
                && m.text().contains("cancelled")), "被取消的兄弟应 isError 且含 cancelled: "
                + results.stream().map(Message::text).toList());
        // 循环继续进入下一轮（done），整轮不中断
        assertEquals(2, seen.size(), "fail-fast 整轮不中断，应进入下一轮");
    }

    // ===== fail-fast 保留已完成结果：完成者在先、异常者在后，前者的成功结果不丢 =====
    @Test
    void failFastKeepsCompletedResultOfEarlierSibling() throws Exception {
        List<String> log = new CopyOnWriteArrayList<>();
        List<Context> seen = new ArrayList<>();
        CyclicBarrier bothArrived = new CyclicBarrier(2);

        ProbeTool good = new ProbeTool("good", ToolDefinition.ToolKind.READ_ONLY, log, id -> {
            awaitQuiet(bothArrived); // 与 bad 并发，先返回成功
            return okRan(id);
        });
        ProbeTool bad = new ProbeTool("bad", ToolDefinition.ToolKind.READ_ONLY, log, id -> {
            awaitQuiet(bothArrived);
            throw new RuntimeException("boom");
        });

        AgentLoop loop = new AgentLoop(fakeTurns(List.of(tc("a", "good"), tc("b", "bad")), seen),
                MODEL, "sys", List.of(good, bad), 5);
        loop.run(List.of(Message.user("hi")), null);

        List<Message> results = seen.get(1).messages.stream()
                .filter(m -> m.role == Message.Role.toolResult).toList();
        assertEquals(2, results.size());
        // good 的已完成成功结果保留（不被 fail-fast 抹掉）
        Message goodRes = results.stream().filter(m -> "a".equals(m.content.get(0).toolCallId)).findFirst().orElseThrow();
        assertFalse(goodRes.content.get(0).isError, "已完成兄弟的结果应保留为成功");
        assertTrue(goodRes.text().contains("ran:a"));
        Message badRes = results.stream().filter(m -> "b".equals(m.content.get(0).toolCallId)).findFirst().orElseThrow();
        assertTrue(badRes.content.get(0).isError && badRes.text().contains("boom"));
    }

    // ===== 钩子 BLOCK 不算异常、不触发 fail-fast：并行组内一 BLOCK 兄弟照跑 =====
    @Test
    void hookBlockDoesNotTriggerFailFastSiblingStillRuns() throws Exception {
        List<String> log = new CopyOnWriteArrayList<>();
        List<Context> seen = new ArrayList<>();

        ProbeTool blocked = new ProbeTool("blocked", ToolDefinition.ToolKind.READ_ONLY, log,
                AgentParallelExecutionTest::okRan);
        ProbeTool sibling = new ProbeTool("sibling", ToolDefinition.ToolKind.READ_ONLY, log,
                AgentParallelExecutionTest::okRan);

        ToolHook blockFirst = new ToolHook() {
            @Override public ToolDecision beforeToolCall(ToolCallEvent e) {
                return "blocked".equals(e.toolCall().name) ? ToolDecision.block("gate") : ToolDecision.proceed();
            }
        };
        AgentLoop loop = new AgentLoop(
                fakeTurns(List.of(tc("a", "blocked"), tc("b", "sibling")), seen),
                MODEL, "sys", List.of(blocked, sibling), 5, List.of(blockFirst));
        loop.run(List.of(Message.user("hi")), null);

        // BLOCK 是 isError 结果而非异常：兄弟不被取消，照常执行
        assertFalse(log.contains("enter:blocked"), "BLOCK 短路该工具 execute，不应进入 blocked");
        assertTrue(log.contains("enter:sibling"), "兄弟工具应照常执行（BLOCK 不触发 fail-fast）");
        List<Message> results = seen.get(1).messages.stream()
                .filter(m -> m.role == Message.Role.toolResult).toList();
        assertEquals(2, results.size(), "BLOCK 与兄弟各一结果，均回 LLM");
        Message blockedRes = results.stream().filter(m -> "a".equals(m.content.get(0).toolCallId)).findFirst().orElseThrow();
        assertTrue(blockedRes.content.get(0).isError && blockedRes.text().contains("blocked by hook"));
        Message sibRes = results.stream().filter(m -> "b".equals(m.content.get(0).toolCallId)).findFirst().orElseThrow();
        assertFalse(sibRes.content.get(0).isError, "兄弟不应被取消/失败");
    }

    // ===== BashTool 中断：sleep 30 被 interrupt 后 2s 内返回 error 且进程 destroy =====
    @Test
    void bashInterruptDestroysProcessAndReturnsError(@TempDir Path tmp) throws Exception {
        Path pidFile = tmp.resolve("pid.txt");
        BashTool bash = new BashTool(tmp, 30_000);
        AtomicReference<ToolResult> res = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();

        Thread runner = new Thread(() -> {
            try {
                // exec 替换 shell，进程 PID 即 $$（destroyForcibly 直接命中 sleep）
                res.set(bash.execute("c1", Map.of(
                        "command", "echo $$ > pid.txt && exec sleep 30")));
            } catch (Throwable t) {
                err.set(t);
            }
        }, "bash-test-runner");
        runner.start();

        // 等待子进程写出 pid（最多 2s，非 timing 断言：barrier 等文件出现）
        long deadline = System.currentTimeMillis() + 2000;
        while (!Files.exists(pidFile) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(Files.exists(pidFile), "子进程应已写出 PID（pid.txt 缺失说明命令未启动）");

        runner.interrupt();
        runner.join(2000);
        assertFalse(runner.isAlive(), "中断后 execute 应在 2s 内返回");
        assertNull(err.get(), "execute 应捕获中断返回 error 而非抛出: " + err.get());

        ToolResult r = res.get();
        assertNotNull(r, "应返回结果");
        assertTrue(r.isError(), "中断应返回 isError");
        assertTrue(r.content().contains("命令被中断"), "内容应标记命令被中断: " + r.content());

        // 进程已被 destroy：PID 不再存活
        long pid = Long.parseLong(Files.readString(pidFile).trim());
        boolean alive = ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
        assertFalse(alive, "被中断后子进程应 destroyForcibly，PID " + pid + " 不应存活");
    }

    // ===== 未知工具在并行 turn 下仍走串行短路、不发事件 =====
    @Test
    void unknownToolInMultiCallTurnStaysSerialShortCircuit() throws Exception {
        List<String> log = new CopyOnWriteArrayList<>();
        ProbeTool r1 = new ProbeTool("r1", ToolDefinition.ToolKind.READ_ONLY, log, AgentParallelExecutionTest::okRan);
        AtomicInteger c = new AtomicInteger();
        LlmClient fake = (model, ctx) -> {
            int n = c.getAndIncrement();
            if (n == 0) {
                return assistant(List.of(Message.Content.toolCall(tc("a", "r1")),
                        Message.Content.toolCall(tc("b", "ghost"))), "toolCalls");
            }
            return end("done");
        };
        List<AgentEvent> events = new ArrayList<>();
        AgentLoop loop = new AgentLoop(fake, MODEL, "sys", List.of(r1), 5, List.of());
        loop.run(List.of(Message.user("hi")), events::add);

        // 未知工具不走钩子、不发其 ToolResultEvent；r1 发自己的
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.ToolStart ts
                && "r1".equals(ts.toolCall().name)), "r1 应发 ToolStart");
        assertTrue(events.stream().noneMatch(e -> e instanceof AgentEvent.ToolStart ts
                && "ghost".equals(ts.toolCall().name)), "未知工具不应发 ToolStart");
    }

    // ===== 修复轮 1：ToolResultEvent 按完成时序由 worker 发射，LLM 结果仍按发出顺序 =====
    @Test
    void parallelResultEventsInCompletionOrderButCollectedInLlmOrder() throws Exception {
        List<String> log = new CopyOnWriteArrayList<>();
        List<Context> seen = new ArrayList<>();
        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        CyclicBarrier bothArrived = new CyclicBarrier(2);
        AtomicReference<String> afterHookThread = new AtomicReference<>();

        // fast（LLM 位置靠后）：barrier 后立即返回；slow（位置靠前）：barrier 后等观察到 fast 的 ToolResultEvent 才返回
        ProbeTool slow = new ProbeTool("slow", ToolDefinition.ToolKind.READ_ONLY, log, id -> {
            awaitQuiet(bothArrived);
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline) {
                boolean fastEmitted = events.stream().anyMatch(e -> e instanceof AgentEvent.ToolResultEvent tre
                        && "fast".equals(tre.toolCall().name));
                if (fastEmitted) return okRan(id);
                nap(10);
            }
            throw new IllegalStateException("fast 的 ToolResultEvent 未在 5s 内按完成时序发射");
        });
        ProbeTool fast = new ProbeTool("fast", ToolDefinition.ToolKind.READ_ONLY, log, id -> {
            awaitQuiet(bothArrived);
            return okRan(id);
        });
        ToolHook captureAfterThread = new ToolHook() {
            @Override public void afterToolCall(AgentEvent.ToolResultEvent e) {
                afterHookThread.set(Thread.currentThread().getName());
            }
        };

        AgentLoop loop = new AgentLoop(fakeTurns(List.of(tc("a", "slow"), tc("b", "fast")), seen),
                MODEL, "sys", List.of(slow, fast), 5, List.of(captureAfterThread));
        loop.run(List.of(Message.user("hi")), events::add);

        // 完成时序：fast 的事件先于 slow（fast 先完成）
        int fastIdx = -1, slowIdx = -1;
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i) instanceof AgentEvent.ToolResultEvent tre) {
                if ("fast".equals(tre.toolCall().name) && fastIdx < 0) fastIdx = i;
                if ("slow".equals(tre.toolCall().name) && slowIdx < 0) slowIdx = i;
            }
        }
        assertTrue(fastIdx >= 0 && slowIdx >= 0 && fastIdx < slowIdx,
                "快者（LLM 位置靠后）的 ToolResultEvent 应先发射；事件序=" + events.stream()
                        .filter(e -> e instanceof AgentEvent.ToolResultEvent)
                        .map(e -> ((AgentEvent.ToolResultEvent) e).toolCall().name).toList());
        // 回 LLM 的结果仍严格按发出顺序 a,b
        List<String> ids = seen.get(1).messages.stream()
                .filter(m -> m.role == Message.Role.toolResult)
                .map(m -> m.content.get(0).toolCallId).toList();
        assertEquals(List.of("a", "b"), ids, "LLM 结果序保持发出顺序");
        // afterToolCall 在 worker 线程上被调用（并行组）
        assertNotNull(afterHookThread.get());
        assertTrue(afterHookThread.get().startsWith("mini-code-tool-"),
                "afterToolCall 应在并行组工作线程执行，实际=" + afterHookThread.get());
    }

    // ===== 修复轮 2：fail-fast 跨 LLM 位置——慢者在前、快抛异常者在后，慢者仍被取消 =====
    @Test
    void failFastCancelsEarlierInFlightSibling() throws Exception {
        List<String> log = new CopyOnWriteArrayList<>();
        List<Context> seen = new ArrayList<>();
        java.util.concurrent.CountDownLatch slowStarted = new java.util.concurrent.CountDownLatch(1);

        ProbeTool slow = new ProbeTool("slow", ToolDefinition.ToolKind.READ_ONLY, log, id -> {
            slowStarted.countDown();
            while (!Thread.currentThread().isInterrupted()) nap(25);
            return ToolResult.error("slow-interrupted"); // claim 已输给主线程，此结果被丢弃
        });
        ProbeTool fastFail = new ProbeTool("fastFail", ToolDefinition.ToolKind.READ_ONLY, log, id -> {
            try { slowStarted.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignore) { }
            throw new RuntimeException("boom");
        });

        long t0 = System.nanoTime();
        AgentLoop loop = new AgentLoop(fakeTurns(List.of(tc("a", "slow"), tc("b", "fastFail")), seen),
                MODEL, "sys", List.of(slow, fastFail), 5);
        loop.run(List.of(Message.user("hi")), null);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        assertTrue(elapsedMs < 4000, "fail-fast 应立即取消在途慢者而非按 LLM 位置阻塞等待，耗时=" + elapsedMs + "ms");
        List<Message> results = seen.get(1).messages.stream()
                .filter(m -> m.role == Message.Role.toolResult).toList();
        assertEquals(2, results.size());
        Message slowRes = results.stream().filter(m -> "a".equals(m.content.get(0).toolCallId)).findFirst().orElseThrow();
        assertTrue(slowRes.content.get(0).isError && slowRes.text().contains("cancelled"),
                "位置靠前的在途慢者应被取消为 isError「cancelled」，实际=" + slowRes.text());
        Message failRes = results.stream().filter(m -> "b".equals(m.content.get(0).toolCallId)).findFirst().orElseThrow();
        assertTrue(failRes.content.get(0).isError && failRes.text().contains("boom"));
        assertEquals(2, seen.size(), "fail-fast 整轮不中断，应进入下一轮");
    }

    // ===== 修复轮 3（US-9）：回合触发器在工具段执行期间置位 → 并行组被 cancel → 子进程被 destroy =====
    @Test
    void turnTriggerCancelDuringParallelGroupInterruptsWorkerAndCleansUp() throws Exception {
        List<String> log = new CopyOnWriteArrayList<>();
        List<Context> seen = new ArrayList<>();
        AtomicBoolean cancelled = new AtomicBoolean(false);
        java.util.concurrent.CountDownLatch procStarted = new java.util.concurrent.CountDownLatch(1);
        AtomicReference<Long> childPid = new AtomicReference<>();

        ProbeTool proc = new ProbeTool("proc", ToolDefinition.ToolKind.READ_ONLY, log, id -> {
            try {
                Process p = new ProcessBuilder("sleep", "60").start();
                childPid.set(p.pid());
                procStarted.countDown();
                try {
                    Thread.sleep(60_000); // 睡到被 cancel(true) 中断
                } catch (InterruptedException ie) {
                    // 预期中断路径
                }
                p.destroyForcibly();
                p.waitFor(); // 同步等子进程退出，保证断言确定性
                return ToolResult.error("interrupted");
            } catch (Exception e) {
                return ToolResult.error("proc-tool-failed: " + e.getMessage());
            }
        });
        ProbeTool flip = new ProbeTool("flip", ToolDefinition.ToolKind.READ_ONLY, log, id -> {
            try { procStarted.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignore) { }
            cancelled.set(true);
            return okRan(id);
        });
        InterruptTrigger trigger = new InterruptTrigger() {
            @Override public boolean isCancelled() { return cancelled.get(); }
            @Override public void close() { }
        };

        AgentLoop loop = new AgentLoop(
                fakeTurns(List.of(tc("a", "proc"), tc("b", "flip")), seen),
                MODEL, "sys", List.of(proc, flip), 5, List.of(), () -> trigger);
        loop.run(List.of(Message.user("hi")), null);

        assertNotNull(childPid.get(), "子进程应已启动");
        assertFalse(ProcessHandle.of(childPid.get()).map(ProcessHandle::isAlive).orElse(false),
                "trigger 置位 → cancel(true) → worker 中断 → 子进程应已 destroy（PID " + childPid.get() + "）");
        List<Message> results = seen.get(1).messages.stream()
                .filter(m -> m.role == Message.Role.toolResult).toList();
        Message procRes = results.stream().filter(m -> "a".equals(m.content.get(0).toolCallId)).findFirst().orElseThrow();
        assertTrue(procRes.content.get(0).isError && procRes.text().contains("cancelled"),
                "被回合中止的工具应得 cancelled 结果，实际=" + procRes.text());
        assertEquals(2, seen.size(), "中止处理后整轮不中断，应进入下一轮");
    }

    // ===== 修复轮 4（US-9b）：段前中止——trigger 在工具段开始前已置位 → 全部填中止结果、工具不执行 =====
    @Test
    void triggerSetBeforeToolSegmentYieldsAbortedWithoutExecuting() throws Exception {
        List<String> log = new CopyOnWriteArrayList<>();
        List<Context> seen = new ArrayList<>();
        ProbeTool r1 = new ProbeTool("r1", ToolDefinition.ToolKind.READ_ONLY, log, AgentParallelExecutionTest::okRan);
        ProbeTool r2 = new ProbeTool("r2", ToolDefinition.ToolKind.READ_ONLY, log, AgentParallelExecutionTest::okRan);
        InterruptTrigger aborted = new InterruptTrigger() {
            @Override public boolean isCancelled() { return true; }
            @Override public void close() { }
        };

        AgentLoop loop = new AgentLoop(fakeTurns(List.of(tc("a", "r1"), tc("b", "r2")), seen),
                MODEL, "sys", List.of(r1, r2), 5, List.of(), () -> aborted);
        loop.run(List.of(Message.user("hi")), null);

        assertTrue(log.isEmpty(), "段前中止：任何工具都不应进入 execute，实际=" + log);
        List<Message> results = seen.get(1).messages.stream()
                .filter(m -> m.role == Message.Role.toolResult).toList();
        assertEquals(2, results.size(), "全部工具得到中止结果并回 LLM");
        assertTrue(results.stream().allMatch(m -> m.content.get(0).isError && m.text().contains("cancelled")),
                "中止结果应 isError 且含 cancelled，实际=" + results.stream().map(Message::text).toList());
        assertEquals(2, seen.size(), "段前中止整轮不中断，应进入下一轮");
    }

    // ===== helper 消息构造 =====
    private static Message assistant(List<Message.Content> cs, String stopReason) {
        Message m = new Message();
        m.role = Message.Role.assistant;
        m.content = cs;
        m.stopReason = stopReason;
        return m;
    }

    private static Message end(String text) {
        Message m = new Message();
        m.role = Message.Role.assistant;
        m.content = List.of(Message.Content.text(text));
        m.stopReason = "end";
        return m;
    }

    private static void nap(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
