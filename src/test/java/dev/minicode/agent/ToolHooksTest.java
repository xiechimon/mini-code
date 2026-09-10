package dev.minicode.agent;

import dev.minicode.ai.Context;
import dev.minicode.ai.LlmClient;
import dev.minicode.ai.Message;
import dev.minicode.ai.Model;
import dev.minicode.tools.ReadTool;
import dev.minicode.tools.ToolDefinition;
import dev.minicode.tools.ToolResult;
import dev.minicode.tools.WriteTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 01 票：before/afterToolCall 工具钩子（三档 ToolDecision）的 AgentLoop 接线验收。
 * 主缝 = AgentLoop + fake LlmClient（lambda + AtomicInteger 回合计数，参考 AgentLoopTest）。
 */
class ToolHooksTest {

    // ===== PROCEED：工具正常执行，before/after 各记录一次 =====
    @Test
    void proceedRecordsBeforeAndAfterOnce(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("a.txt"), "AAA");
        List<ToolDefinition> tools = List.of(new ReadTool(tmp));
        AtomicInteger call = new AtomicInteger(0);
        LlmClient fake = (model, ctx) -> turn(call.getAndIncrement(),
                new Message.ToolCall("t1", "read", Map.of("path", "a.txt"), "{\"path\":\"a.txt\"}"), "ok");

        RecordingHook hook = new RecordingHook();
        AgentLoop loop = new AgentLoop(fake, Model.opencodeGo("k"), "sys", tools, 5, List.of(hook));
        List<Message> out = loop.run(List.of(Message.user("hi")), null);

        // 工具正常执行，结果回模型
        assertTrue(out.stream().anyMatch(m -> m.role == Message.Role.toolResult && m.text().contains("AAA")));
        assertEquals(List.of("before:read", "after:read"), hook.sequence, "PROCEED 应恰好各记录一次");
    }

    // ===== BLOCK：工具不执行（无副作用）、isError 含 reason、不发 ToolStart、LLM 收到结果、循环继续 =====
    @Test
    void blockSkipsToolAndFeedsErrorResultBackToLlm(@TempDir Path tmp) throws Exception {
        List<ToolDefinition> tools = List.of(new WriteTool(tmp));
        List<Context> seen = new ArrayList<>();
        AtomicInteger call = new AtomicInteger(0);
        LlmClient fake = (model, ctx) -> {
            seen.add(ctx);
            int c = call.getAndIncrement();
            if (c == 0) {
                return assistant(Message.Content.toolCall(
                        new Message.ToolCall("w1", "write", Map.of("path", "victim.txt", "content", "x"), "{}")), "toolCalls");
            }
            return end("done");
        };

        RecordingHook hook = new RecordingHook(e -> ToolDecision.block("危险写盘"));
        List<AgentEvent> events = new ArrayList<>();
        AgentLoop loop = new AgentLoop(fake, Model.opencodeGo("k"), "sys", tools, 5, List.of(hook));
        loop.run(List.of(Message.user("写 victim")), events::add);

        // 文件系统无副作用
        assertFalse(Files.exists(tmp.resolve("victim.txt")), "BLOCK 时工具不得执行，文件不应被创建");
        // 不发 ToolStart
        assertTrue(events.stream().noneMatch(e -> e instanceof AgentEvent.ToolStart), "BLOCK 不应发 ToolStart");
        // ToolResultEvent isError 且含 reason
        AgentEvent.ToolResultEvent tre = events.stream()
                .filter(e -> e instanceof AgentEvent.ToolResultEvent).map(e -> (AgentEvent.ToolResultEvent) e)
                .findFirst().orElseThrow();
        assertTrue(tre.isError(), "BLOCK 结果应 isError=true");
        assertTrue(tre.output().contains("blocked by hook") && tre.output().contains("危险写盘"), "输出应含 reason: " + tre.output());
        // LLM 下一轮能看到该 isError 结果
        assertEquals(2, seen.size(), "循环应继续到第二轮");
        Message blockedResult = seen.get(1).messages.stream()
                .filter(m -> m.role == Message.Role.toolResult).findFirst().orElseThrow();
        assertTrue(blockedResult.content.get(0).isError, "回传给 LLM 的应为 isError 结果");
        assertTrue(blockedResult.text().contains("危险写盘"));
        // BLOCK 仍走 afterToolCall（观测钩子照常）
        assertEquals(List.of("before:write", "after:write"), hook.sequence);
    }

    // ===== MODIFY：工具收到修改后的 arguments；后续钩子的 beforeToolCall 看到 modifiedCall =====
    @Test
    void modifyReplacesToolCallForToolAndNextHook(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("foo.txt"), "FOO-SECRET");
        Files.writeString(tmp.resolve("bar.txt"), "BAR-PUBLIC");
        List<ToolDefinition> tools = List.of(new ReadTool(tmp));
        AtomicInteger call = new AtomicInteger(0);
        // 模型请求读 foo，钩子改写为 bar
        LlmClient fake = (model, ctx) -> turn(call.getAndIncrement(),
                new Message.ToolCall("t1", "read", Map.of("path", "foo.txt"), "{\"path\":\"foo.txt\"}"), "ok");

        RecordingHook h1 = new RecordingHook(e -> ToolDecision.modify(
                new Message.ToolCall("t1", "read", Map.of("path", "bar.txt"), "{\"path\":\"bar.txt\"}")));
        RecordingHook h2 = new RecordingHook();
        AgentLoop loop = new AgentLoop(fake, Model.opencodeGo("k"), "sys", tools, 5, List.of(h1, h2));
        List<Message> out = loop.run(List.of(Message.user("读 foo")), null);

        // 工具读到改写后的 bar.txt
        assertTrue(out.stream().anyMatch(m -> m.role == Message.Role.toolResult && m.text().contains("BAR-PUBLIC")),
                "工具应读到改写后的路径内容");
        // 后续钩子看到 modifiedCall
        assertEquals("bar.txt", h2.lastEvent.arguments().get("path"), "h2 的 beforeToolCall 应看到改写后的 path");
        assertEquals("bar.txt", h2.lastEvent.toolCall().arguments.get("path"), "toolCall.arguments 亦应为改写后的");
    }

    // ===== 双钩子短路：hook1 BLOCK → hook2.beforeToolCall 未被调用 =====
    @Test
    void hook1BlockShortCircuitsHook2Before(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("a.txt"), "AAA");
        List<ToolDefinition> tools = List.of(new ReadTool(tmp));
        AtomicInteger call = new AtomicInteger(0);
        LlmClient fake = (model, ctx) -> turn(call.getAndIncrement(),
                new Message.ToolCall("t1", "read", Map.of("path", "a.txt"), "{}"), "ok");

        RecordingHook h1 = new RecordingHook(e -> ToolDecision.block("nope"));
        RecordingHook h2 = new RecordingHook();
        AgentLoop loop = new AgentLoop(fake, Model.opencodeGo("k"), "sys", tools, 5, List.of(h1, h2));
        loop.run(List.of(Message.user("hi")), null);

        // h1 被调用（before+after），h2 的 beforeToolCall 被短路、但 afterToolCall 仍按序观测
        assertEquals(List.of("before:read", "after:read"), h1.sequence);
        assertEquals(List.of("after:read"), h2.sequence, "h2 不应看到 before，但 after 照常");
    }

    // ===== beforeToolCall 抛异常 → isError「hook failed」结果、工具不执行 =====
    @Test
    void beforeToolCallThrowBecomesHookFailedResult(@TempDir Path tmp) throws Exception {
        List<ToolDefinition> tools = List.of(new WriteTool(tmp));
        AtomicInteger call = new AtomicInteger(0);
        LlmClient fake = (model, ctx) -> turn(call.getAndIncrement(),
                new Message.ToolCall("w1", "write", Map.of("path", "boom.txt", "content", "x"), "{}"), "ok");

        RecordingHook hook = new RecordingHook(e -> {
            throw new IllegalStateException("钩子炸了");
        });
        AgentLoop loop = new AgentLoop(fake, Model.opencodeGo("k"), "sys", tools, 5, List.of(hook));
        List<Message> out = loop.run(List.of(Message.user("写 boom")), null);

        assertFalse(Files.exists(tmp.resolve("boom.txt")), "钩子异常时工具不得执行");
        Message result = out.stream().filter(m -> m.role == Message.Role.toolResult).findFirst().orElseThrow();
        assertTrue(result.content.get(0).isError);
        assertTrue(result.text().contains("hook failed") && result.text().contains("钩子炸了"), "结果应含 hook failed: " + result.text());
    }

    // ===== afterToolCall 抛异常 → 结果不变、不崩、其余钩子照常 =====
    @Test
    void afterToolCallThrowDoesNotAffectResultOrOtherHooks(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("a.txt"), "AAA");
        List<ToolDefinition> tools = List.of(new ReadTool(tmp));
        AtomicInteger call = new AtomicInteger(0);
        LlmClient fake = (model, ctx) -> turn(call.getAndIncrement(),
                new Message.ToolCall("t1", "read", Map.of("path", "a.txt"), "{}"), "ok");

        RecordingHook throwingAfter = new RecordingHook() {
            @Override
            public void afterToolCall(AgentEvent.ToolResultEvent event) {
                super.afterToolCall(event);
                throw new IllegalStateException("after 炸了");
            }
        };
        RecordingHook normal = new RecordingHook();
        AgentLoop loop = new AgentLoop(fake, Model.opencodeGo("k"), "sys", tools, 5, List.of(throwingAfter, normal));
        List<Message> out = loop.run(List.of(Message.user("hi")), null);

        // 工具正常执行，结果不变
        assertTrue(out.stream().anyMatch(m -> m.role == Message.Role.toolResult && m.text().contains("AAA")));
        // 抛异常的钩子照常记录 after；其余钩子照常
        assertEquals(List.of("before:read", "after:read"), throwingAfter.sequence);
        assertEquals(List.of("before:read", "after:read"), normal.sequence);
    }

    // ===== stopReason=length 分支不走钩子（构造错误结果，非真执行） =====
    @Test
    void lengthTruncationDoesNotInvokeHooks(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("a.txt"), "AAA");
        List<ToolDefinition> tools = List.of(new ReadTool(tmp));
        AtomicInteger call = new AtomicInteger(0);
        LlmClient fake = (model, ctx) -> {
            int c = call.getAndIncrement();
            if (c == 0) {
                return assistant(Message.Content.toolCall(
                        new Message.ToolCall("t1", "read", Map.of("path", "a.txt"), "{}")), "length");
            }
            return end("recovered");
        };

        RecordingHook hook = new RecordingHook();
        AgentLoop loop = new AgentLoop(fake, Model.opencodeGo("k"), "sys", tools, 5, List.of(hook));
        List<Message> out = loop.run(List.of(Message.user("hi")), null);

        assertTrue(hook.sequence.isEmpty(), "length 截断路径不应触发任何钩子");
        // 仍有 isError 截断结果
        assertTrue(out.stream().anyMatch(m -> m.role == Message.Role.toolResult && m.text().contains("截断")));
    }

    // ===== 未知工具不走钩子、不发 ToolStart/ToolResultEvent（与 MVP1 一致） =====
    @Test
    void unknownToolDoesNotInvokeHooks(@TempDir Path tmp) throws Exception {
        List<ToolDefinition> tools = List.of(new ReadTool(tmp));
        AtomicInteger call = new AtomicInteger(0);
        LlmClient fake = (model, ctx) -> turn(call.getAndIncrement(),
                new Message.ToolCall("t1", "ghost", Map.of(), "{}"), "ok");

        RecordingHook hook = new RecordingHook();
        List<AgentEvent> events = new ArrayList<>();
        AgentLoop loop = new AgentLoop(fake, Model.opencodeGo("k"), "sys", tools, 5, List.of(hook));
        List<Message> out = loop.run(List.of(Message.user("hi")), events::add);

        assertTrue(hook.sequence.isEmpty(), "未知工具不应触发钩子");
        assertTrue(events.stream().noneMatch(e -> e instanceof AgentEvent.ToolStart), "未知工具不应发 ToolStart");
        assertTrue(events.stream().noneMatch(e -> e instanceof AgentEvent.ToolResultEvent), "未知工具不应发 ToolResultEvent（与 MVP1 一致）");
        assertTrue(out.stream().anyMatch(m -> m.role == Message.Role.toolResult && m.text().contains("未知工具")),
                "应仍回未知工具 isError 结果消息");
    }

    // ===== 无钩子单工具回合：行为与 MVP1 完全一致（零回归显式锚点） =====
    @Test
    void emptyHooksSingleToolRoundUnchanged(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("a.txt"), "AAA");
        List<ToolDefinition> tools = List.of(new ReadTool(tmp));
        AtomicInteger call = new AtomicInteger(0);
        LlmClient fake = (model, ctx) -> turn(call.getAndIncrement(),
                new Message.ToolCall("t1", "read", Map.of("path", "a.txt"), "{}"), "ok");

        List<AgentEvent> events = new ArrayList<>();
        // 空钩子列表：事件序列应与 MVP1 完全一致
        AgentLoop loop = new AgentLoop(fake, Model.opencodeGo("k"), "sys", tools, 5, List.of());
        List<Message> out = loop.run(List.of(Message.user("hi")), events::add);
        // user + assistant(read) + toolResult + assistant(end) = 4
        assertEquals(4, out.size(), "无钩子单工具回合消息数与 MVP1 一致");
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.ToolStart));
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.ToolResultEvent));
    }

    // ===== 自定义 fake 工具执行可断言 executed 标志（确保 BLOCK 真短路 execute） =====
    @Test
    void blockPreventsExecuteInvocation(@TempDir Path tmp) throws Exception {
        AtomicBoolean executed = new AtomicBoolean(false);
        ToolDefinition spy = new ToolDefinition() {
            @Override public String name() { return "spy"; }
            @Override public String description() { return "spy"; }
            @Override public com.fasterxml.jackson.databind.JsonNode parameters() {
                return new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
            }
            @Override public ToolResult execute(String callId, Map<String, Object> arguments) {
                executed.set(true);
                return ToolResult.ok("ran");
            }
        };
        AtomicInteger call = new AtomicInteger(0);
        LlmClient fake = (model, ctx) -> turn(call.getAndIncrement(),
                new Message.ToolCall("s1", "spy", Map.of(), "{}"), "ok");

        RecordingHook hook = new RecordingHook(e -> ToolDecision.block("deny-all"));
        AgentLoop loop = new AgentLoop(fake, Model.opencodeGo("k"), "sys", List.of(spy), 5, List.of(hook));
        loop.run(List.of(Message.user("hi")), null);
        assertFalse(executed.get(), "BLOCK 后 def.execute 不得被调用");
    }

    // ===== 辅助 =====

    /** 第 0 回合发一个工具调用，其余回合纯文本结束 */
    private static Message turn(int c, Message.ToolCall tc, String finalText) {
        if (c == 0) return assistant(Message.Content.toolCall(tc), "toolCalls");
        return end(finalText);
    }

    private static Message assistant(Message.Content content, String stopReason) {
        Message m = new Message();
        m.role = Message.Role.assistant;
        m.content = List.of(content);
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

}
