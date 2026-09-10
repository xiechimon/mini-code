package dev.minicode.cli;

import dev.minicode.agent.AgentEvent;
import dev.minicode.ai.Message;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Main 的流式接线单测（块级策略）：同步路径 aborted 标记、管道无控制序列、SIGINT 触发器生命周期。
 * 块级渲染行为（进度/块完成/围栏）见 BlockStreamerTest。
 */
class MainStreamingTest {

    private PrintStream originalOut;
    private ByteArrayOutputStream captured;

    @BeforeEach
    void captureOut() {
        originalOut = System.out;
        captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, java.nio.charset.StandardCharsets.UTF_8));
    }

    @AfterEach
    void restoreOut() {
        System.setOut(originalOut);
    }

    private String capturedStr() {
        return captured.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void abortedMarkerViaSyncPath() {
        Message aborted = Message.assistant(List.of(Message.Content.text("partial")), "aborted");
        String out = EventRenderer.render(new AgentEvent.MessageEnd(aborted), Style.plain(),
                TurnStats.inferred(java.time.Duration.ZERO), 80);
        assertTrue(out.contains("⏹ 已中断"), "同步路径 aborted 应含标记");
        assertTrue(out.contains("partial"));
        String outColor = EventRenderer.render(new AgentEvent.MessageEnd(aborted), Style.colored(),
                TurnStats.inferred(java.time.Duration.ZERO), 80);
        assertTrue(outColor.contains("⏹ 已中断"));
        assertTrue(outColor.contains(Style.ANSI_GRAY));
    }

    @Test
    void pipeStylePlainHasNoControlSequences() {
        Message msg = Message.assistant(List.of(Message.Content.text("hello world")), "end");
        String out = EventRenderer.render(new AgentEvent.MessageEnd(msg), Style.plain(),
                TurnStats.inferred(java.time.Duration.ZERO), 80);
        assertFalse(out.contains("\u001B["), "管道/去色不应含任何控制序列");
        assertTrue(out.contains("hello world"));
    }

    @Test
    void sigIntTriggerLifecycle() throws Exception {
        // 受限环境（非 Unix）可能降级为永不取消：验证 close 不抛且可重复
        SigIntInterruptTrigger t = new SigIntInterruptTrigger();
        assertFalse(t.isCancelled());
        assertDoesNotThrow(t::close);
        assertFalse(t.isCancelled());
        assertDoesNotThrow(t::close);
    }
}
