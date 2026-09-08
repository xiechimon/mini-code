package dev.minicode.agent;

import dev.minicode.ai.*;
import org.junit.jupiter.api.Test;

import java.util.Map;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 04 票中断与流式在 AgentLoop 缝的测试——注入触发器可测。
 */
class AgentLoopStreamingTest {

    @Test
    void streamDeltaEventsEmittedInOrder() throws Exception {
        List<String> deltas = List.of("hello ", "world", "!");
        LlmClient streamingFake = new LlmClient() {
            @Override
            public Message chat(Model model, Context context) { return null; }
            @Override
            public Message stream(Model model, Context context, java.util.function.Consumer<String> onDelta, java.util.function.Supplier<Boolean> isCancelled) {
                for (String d : deltas) onDelta.accept(d);
                Message m = new Message();
                m.role = Message.Role.assistant;
                m.content = List.of(Message.Content.text(String.join("", deltas)));
                m.stopReason = "end";
                return m;
            }
        };
        Model model = Model.opencodeGo("kimi-k2.6");
        AgentLoop loop = new AgentLoop(streamingFake, model, "sys", List.of(), 3);

        List<AgentEvent> events = new ArrayList<>();
        loop.run(List.of(Message.user("hi")), events::add);

        // 应有 StreamDelta ×3 + MessageEnd + AgentEnd 等
        long deltaCount = events.stream().filter(e -> e instanceof AgentEvent.StreamDelta).count();
        assertEquals(3, deltaCount);
        List<String> emitted = events.stream()
                .filter(e -> e instanceof AgentEvent.StreamDelta)
                .map(e -> ((AgentEvent.StreamDelta) e).delta())
                .toList();
        assertEquals(deltas, emitted);
        // MessageEnd 文本应为累积
        AgentEvent.MessageEnd me = events.stream()
                .filter(e -> e instanceof AgentEvent.MessageEnd)
                .map(e -> (AgentEvent.MessageEnd) e)
                .findFirst().orElseThrow();
        assertEquals("hello world!", me.message().text());
        assertEquals("end", me.message().stopReason);
    }

    @Test
    void interruptTriggerAbortsWithPartialAndSkipsTools() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        InterruptTrigger trigger = new InterruptTrigger() {
            @Override public boolean isCancelled() { return cancelled.get(); }
            @Override public void close() {}
        };
        // fake：先回调 partial，然后检测取消后返回 aborted
        LlmClient fake = new LlmClient() {
            @Override public Message chat(Model model, Context context) { return null; }
            @Override
            public Message stream(Model model, Context context, java.util.function.Consumer<String> onDelta, java.util.function.Supplier<Boolean> isCancelled) throws Exception {
                onDelta.accept("partial-");
                // 模拟在回调后触发取消
                cancelled.set(true);
                // 再尝试回调一次，但应被视作已取消
                if (isCancelled.get()) {
                    Message m = new Message();
                    m.role = Message.Role.assistant;
                    m.content = List.of(Message.Content.text("partial-"));
                    m.stopReason = "aborted";
                    return m;
                }
                onDelta.accept("more");
                Message m = new Message();
                m.role = Message.Role.assistant;
                m.content = List.of(Message.Content.text("partial-more"));
                m.stopReason = "end";
                return m;
            }
        };
        Model model = Model.opencodeGo("k");
        // 注入可控制的 trigger
        AgentLoop loop = new AgentLoop(fake, model, "sys", List.of(), 3, () -> trigger);

        List<AgentEvent> events = new ArrayList<>();
        List<Message> out = loop.run(List.of(Message.user("hi")), events::add);

        // 应有 1 个 delta，然后 aborted
        long deltas = events.stream().filter(e -> e instanceof AgentEvent.StreamDelta).count();
        assertEquals(1, deltas);
        AgentEvent.MessageEnd me = events.stream().filter(e -> e instanceof AgentEvent.MessageEnd).map(e -> (AgentEvent.MessageEnd) e).findFirst().orElseThrow();
        assertEquals("aborted", me.message().stopReason);
        assertEquals("partial-", me.message().text());
        // 检查 AgentLoop 返回的 history 含 aborted
        assertTrue(out.stream().anyMatch(m -> "aborted".equals(m.stopReason)));
        // 工具不应执行（即使 fake 返回的 message 含 toolCalls 也会被跳过，因 aborted 直接结束）
        assertTrue(events.stream().noneMatch(e -> e instanceof AgentEvent.ToolStart));
    }

    @Test
    void interruptTriggerNotCancelledNormalFlow() throws Exception {
        InterruptTrigger never = () -> false;
        LlmClient fake = (model, ctx) -> {
            Message m = new Message();
            m.role = Message.Role.assistant;
            m.content = List.of(Message.Content.text("ok"));
            m.stopReason = "end";
            return m;
        };
        // 使用 stream 默认路径（不产生 delta）
        AgentLoop loop = new AgentLoop(fake, Model.opencodeGo("k"), "sys", List.of(), 3, () -> never);
        List<AgentEvent> events = new ArrayList<>();
        loop.run(List.of(Message.user("hi")), events::add);
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.MessageEnd));
        assertFalse(events.stream().anyMatch(e -> e instanceof AgentEvent.StreamDelta));
        // 无 aborted
        assertTrue(events.stream().noneMatch(e -> e instanceof AgentEvent.MessageEnd && "aborted".equals(((AgentEvent.MessageEnd) e).message().stopReason)));
    }

    @Test
    void triggerCloseIsCalledAfterStream() throws Exception {
        AtomicInteger closeCount = new AtomicInteger(0);
        InterruptTrigger t = new InterruptTrigger() {
            @Override public boolean isCancelled() { return false; }
            @Override public void close() { closeCount.incrementAndGet(); }
        };
        LlmClient fake = (model, ctx) -> {
            Message m = new Message();
            m.role = Message.Role.assistant;
            m.content = List.of(Message.Content.text("done"));
            m.stopReason = "end";
            return m;
        };
        AgentLoop loop = new AgentLoop(fake, Model.opencodeGo("k"), "sys", List.of(), 3, () -> t);
        loop.run(List.of(Message.user("hi")), null);
        assertEquals(1, closeCount.get(), "每次 stream 后应 close 触发器以注销信号处理器");
        // 多轮应多次 close
        AtomicInteger multiClose = new AtomicInteger(0);
        InterruptTrigger t2 = new InterruptTrigger() {
            @Override public boolean isCancelled() { return false; }
            @Override public void close() { multiClose.incrementAndGet(); }
        };
        AtomicInteger calls = new AtomicInteger(0);
        LlmClient multiFake = (model, ctx) -> {
            int c = calls.getAndIncrement();
            if (c == 0) {
                Message m = new Message();
                m.role = Message.Role.assistant;
                m.content = List.of(Message.Content.toolCall(new Message.ToolCall("1","bash", Map.of("command","echo hi"), "{\"command\":\"echo hi\"}")));
                m.stopReason = "toolCalls";
                return m;
            } else {
                Message m = new Message();
                m.role = Message.Role.assistant;
                m.content = List.of(Message.Content.text("done"));
                m.stopReason = "end";
                return m;
            }
        };
        dev.minicode.tools.BashTool bash = new dev.minicode.tools.BashTool(java.nio.file.Path.of(System.getProperty("user.dir")));
        AgentLoop loop2 = new AgentLoop(multiFake, Model.opencodeGo("k"), "sys", List.of(bash), 5, () -> t2);
        loop2.run(List.of(Message.user("hi")), null);
        assertEquals(2, multiClose.get(), "两轮 stream 应 close 两次");
    }

    @Test
    void defaultStreamZeroCallbacksIsPipelineEquivalent() throws Exception {
        // 管道模式走 default stream（零回调）应等价同步，且无 StreamDelta
        LlmClient fake = (model, ctx) -> {
            Message m = new Message();
            m.role = Message.Role.assistant;
            m.content = List.of(Message.Content.text("sync"));
            m.stopReason = "end";
            return m;
        };
        AgentLoop loop = new AgentLoop(fake, Model.opencodeGo("k"), "sys", List.of(), 3);
        List<AgentEvent> events = new ArrayList<>();
        loop.run(List.of(Message.user("hi")), events::add);
        assertEquals(0, events.stream().filter(e -> e instanceof AgentEvent.StreamDelta).count(), "default stream 不应产生 delta，管道无控制序列");
        AgentEvent.MessageEnd me = events.stream().filter(e -> e instanceof AgentEvent.MessageEnd).map(e -> (AgentEvent.MessageEnd) e).findFirst().orElseThrow();
        assertEquals("sync", me.message().text());
    }

    @Test
    void pipelineDisabledStreamingEmitsZeroDeltaAndUsesChatPath() throws Exception {
        // 管道真正非流式：streamingEnabled=false 时即使 LlmClient 的 stream 会发射 delta，也应零 StreamDelta
        LlmClient streamingFake = new LlmClient() {
            @Override public Message chat(Model model, Context context) {
                Message m = new Message();
                m.role = Message.Role.assistant;
                m.content = List.of(Message.Content.text("sync-fallback"));
                m.stopReason = "end";
                return m;
            }
            @Override public Message stream(Model model, Context context, java.util.function.Consumer<String> onDelta, java.util.function.Supplier<Boolean> isCancelled) {
                onDelta.accept("should-not-emit");
                Message m = new Message();
                m.role = Message.Role.assistant;
                m.content = List.of(Message.Content.text("stream-should-not-be-used"));
                m.stopReason = "end";
                return m;
            }
        };
        Model model = Model.opencodeGo("k");
        // 管道 loop 禁用流式，期望走 chat 路径
        AgentLoop pipelineLoop = new AgentLoop(streamingFake, model, "sys", List.of(), 3, () -> () -> false, false);
        List<AgentEvent> events = new ArrayList<>();
        pipelineLoop.run(List.of(Message.user("hi")), events::add);
        assertEquals(0, events.stream().filter(e -> e instanceof AgentEvent.StreamDelta).count(), "管道模式零 StreamDelta 发射");
        AgentEvent.MessageEnd me = events.stream().filter(e -> e instanceof AgentEvent.MessageEnd).map(e -> (AgentEvent.MessageEnd) e).findFirst().orElseThrow();
        assertEquals("sync-fallback", me.message().text(), "管道应走 chat 同步路径而非 stream");
        assertEquals("end", me.message().stopReason);
    }

    @Test
    void cancellationExceptionWithoutFlagDoesNotBecomeAborted() throws Exception {
        // 假 CancellationException 但未置取消信号，不应误转为 aborted
        LlmClient fake = new LlmClient() {
            @Override public Message chat(Model model, Context context) { return null; }
            @Override public Message stream(Model model, Context context, java.util.function.Consumer<String> onDelta, java.util.function.Supplier<Boolean> isCancelled) {
                throw new java.util.concurrent.CancellationException("fake without cancel");
            }
        };
        InterruptTrigger never = () -> false;
        AgentLoop loop = new AgentLoop(fake, Model.opencodeGo("k"), "sys", List.of(), 3, () -> never);
        assertThrows(java.util.concurrent.CancellationException.class, () -> loop.run(List.of(Message.user("hi")), e -> {}), "未置取消的 CancellationException 不应转为 aborted");
    }

    @Test
    void interruptedExceptionWithoutFlagDoesNotBecomeAborted() throws Exception {
        LlmClient fake = new LlmClient() {
            @Override public Message chat(Model model, Context context) { return null; }
            @Override public Message stream(Model model, Context context, java.util.function.Consumer<String> onDelta, java.util.function.Supplier<Boolean> isCancelled) throws Exception {
                throw new InterruptedException("fake interrupt without cancel");
            }
        };
        InterruptTrigger never = () -> false;
        AgentLoop loop = new AgentLoop(fake, Model.opencodeGo("k"), "sys", List.of(), 3, () -> never);
        assertThrows(InterruptedException.class, () -> loop.run(List.of(Message.user("hi")), e -> {}));
    }
}
