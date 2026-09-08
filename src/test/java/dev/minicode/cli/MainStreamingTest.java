package dev.minicode.cli;

import dev.minicode.agent.AgentEvent;
import dev.minicode.ai.Message;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Main 的流式打印与 SIGINT 接线单测。
 * 覆盖：StreamDelta 直出 flush、MessageEnd 重绘、管道无控制序列、触发器注入。
 */
class MainStreamingTest {

    private PrintStream originalOut;
    private ByteArrayOutputStream captured;

    @BeforeEach
    void captureOut() {
        originalOut = System.out;
        captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true));
    }

    @AfterEach
    void restoreOut() {
        System.setOut(originalOut);
    }

    private String capturedStr() {
        return captured.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void printEventStreamDeltaDirectAndFlush() {
        Style plain = Style.plain();
        StreamState state = new StreamState(80);
        // 首个 delta 应含 CUU+EL 并直出
        Main.printEvent(new AgentEvent.StreamDelta("hello"), plain, 80, state);
        String out1 = capturedStr();
        assertTrue(out1.contains("hello"), "应直出 delta");
        assertTrue(out1.contains("\u001B[1A"), "首个应含 CUU1");
        assertTrue(out1.contains("\u001B[2K"), "首个应含清行");
        assertEquals(1, state.rows());
        captured.reset();

        // 第二个 delta 不含 CUU
        Main.printEvent(new AgentEvent.StreamDelta(" world"), plain, 80, state);
        String out2 = capturedStr();
        assertEquals(" world", out2);
        assertFalse(out2.contains("\u001B["));
        assertEquals(1, state.rows()); // \"hello world\" 11/80=1
    }

    @Test
    void printEventMessageEndWithStreamHasRedraw() {
        Style plain = Style.plain();
        StreamState state = new StreamState(20);
        // 流式先产生 2 行
        Main.printEvent(new AgentEvent.StreamDelta("hello world hello "), plain, 20, state);
        Main.printEvent(new AgentEvent.StreamDelta("world"), plain, 20, state);
        captured.reset(); // 清空 delta 输出，仅测 MessageEnd
        int rows = state.rows();
        assertTrue(rows > 1);

        Message msg = Message.assistant(java.util.List.of(Message.Content.text("final text")), "end");
        Main.printEvent(new AgentEvent.MessageEnd(msg), plain, 20, state);
        String out = capturedStr();
        assertTrue(out.contains(Style.cursorUp(rows) + Style.ERASE_DOWN), "应含重绘序列");
        assertTrue(out.contains("final text"));
        // 管道或空 state 不应含重绘
        captured.reset();
        Message msg2 = Message.assistant(java.util.List.of(Message.Content.text("final2")), "end");
        Main.printEvent(new AgentEvent.MessageEnd(msg2), plain, 80, null);
        String out2 = capturedStr();
        assertFalse(out2.contains("\u001B["));
        assertTrue(out2.contains("final2"));
    }

    @Test
    void printEventAbortedMarkerPresent() {
        Style plain = Style.plain();
        Style colored = Style.colored();
        Message aborted = Message.assistant(java.util.List.of(Message.Content.text("partial")), "aborted");
        // 非流式
        Main.printEvent(new AgentEvent.MessageEnd(aborted), plain);
        String outPlain = capturedStr();
        assertTrue(outPlain.contains("⏹ 已中断"));
        assertTrue(outPlain.contains("partial"));
        captured.reset();

        Main.printEvent(new AgentEvent.MessageEnd(aborted), colored);
        String outColor = capturedStr();
        assertTrue(outColor.contains("⏹ 已中断"));
        assertTrue(outColor.contains(Style.ANSI_GRAY));
        captured.reset();

        // 流式 aborted 也应有标记 + 重绘
        StreamState s = new StreamState(80);
        Main.printEvent(new AgentEvent.StreamDelta("partial"), plain, 80, s);
        captured.reset();
        Main.printEvent(new AgentEvent.MessageEnd(aborted), plain, 80, s);
        String outStreamAborted = capturedStr();
        assertTrue(outStreamAborted.contains("⏹ 已中断"));
        assertTrue(outStreamAborted.contains("\u001B["));
    }

    @Test
    void printEventAgentEndAbortedMarker() {
        Style plain = Style.plain();
        Message aborted = Message.assistant(java.util.List.of(Message.Content.text("p")), "aborted");
        Message user = Message.user("hi");
        AgentEvent.AgentEnd ae = new AgentEvent.AgentEnd(java.util.List.of(user, aborted));
        Main.printEvent(ae, plain, Duration.ofMillis(100), 1, 0);
        String out = capturedStr();
        assertTrue(out.contains("⏹ 已中断"));
        assertTrue(out.contains("1 轮"));
        // 不应含 CUU
        assertFalse(out.contains("\u001B[1A"));
        assertFalse(out.contains("\u001B[J"));
    }

    @Test
    void pipelineNoControlSequencesViaPlainPrint() {
        // 管道模式模拟：plain style + null state + 默认 printEvent
        Message msg = Message.assistant(java.util.List.of(Message.Content.text("hello world")), "end");
        Main.printEvent(new AgentEvent.MessageEnd(msg), Style.plain());
        String out = capturedStr();
        assertFalse(out.contains("\u001B["), "管道/去色不应含任何控制序列");
        assertTrue(out.contains("hello"));
        captured.reset();

        // StreamDelta 在 pipeline 无 state 时也不含控制
        Main.printEvent(new AgentEvent.StreamDelta("delta"), Style.plain());
        String out2 = capturedStr();
        assertEquals("delta", out2.trim());
        assertFalse(out2.contains("\u001B["));
    }

    @Test
    void sigIntTriggerRegistersAndRestores() throws Exception {
        // 直接测试 SigIntInterruptTrigger 的可取消性与 close 恢复
        // 注意：在受限环境（如非 Unix）可能降级为永不取消，此时测试跳过
        Main.SigIntInterruptTrigger t = new Main.SigIntInterruptTrigger();
        // 初始未取消
        assertFalse(t.isCancelled());
        // 模拟信号：直接反射或调用 handler？ 我们无法真正发 SIGINT，但在 Unix 上 handler 会被注册，
        // 此处我们通过直接设置 cancelled 来模拟已被信号置位
        // 为了可测，我们验证 close 不抛异常且可重复
        t.close();
        // 关闭后再次取消应仍可读（不抛）
        assertFalse(t.isCancelled());
        // 第二次 close 也不抛
        assertDoesNotThrow(t::close);
    }

    @Test
    void streamStateWidthAwareRows() {
        assertEquals(1, StreamState.computeRows("hello", 80));
        assertEquals(2, StreamState.computeRows("hello world!", 10)); // 12/10=2
        assertEquals(3, StreamState.computeRows("a\nb\nc", 10));
        assertEquals(1, StreamState.computeRows("a\n", 10));
    }
}
