package dev.minicode.cli;

import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.terminal.impl.ExternalTerminal;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link TerminalInterruptTrigger} 行为单测：聚焦「按 Esc 也能中断」这一新增能力。
 * <p>
 * 用 ExternalTerminal(dumb) + ByteArrayInputStream 喂入确定字节序列：pump 线程同步把字节处理进
 * 终端的 slave 管道，观察线程经 {@code terminal.reader()} 读取。裸 Esc → 置位取消；方向键转义序列
 * → 吞掉不取消；普通字节 → 丢弃不取消；close 幂等并还原 raw 前属性；terminal 为 null → 退化仅 SIGINT。
 * </p>
 */
class TerminalInterruptTriggerTest {

    /** 在截止时间前轮询断言 cancelled 变为期望值，避免观察线程异步导致的偶发。 */
    private static void awaitCancelled(TerminalInterruptTrigger t, boolean expected, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (t.isCancelled() == expected) return;
            Thread.sleep(10);
        }
        assertEquals(expected, t.isCancelled(), "cancelled 未在 " + timeoutMs + "ms 内变为 " + expected);
    }

    private static Terminal terminal(byte[] input) throws Exception {
        return new ExternalTerminal("esc-test", "dumb",
                new ByteArrayInputStream(input), new ByteArrayOutputStream(), StandardCharsets.UTF_8);
    }

    @Test
    void bareEscCancels() throws Exception {
        try (Terminal term = terminal(new byte[]{0x1B})) {
            TerminalInterruptTrigger t = new TerminalInterruptTrigger(term);
            try {
                awaitCancelled(t, true, 2000);
            } finally {
                t.close();
            }
        }
    }

    @Test
    void arrowKeyEscapeSequenceDoesNotCancel() throws Exception {
        // ESC [ D（左方向键）：其后紧跟字节，应整段吞掉而非中断
        try (Terminal term = terminal(new byte[]{0x1B, '[', 'D'})) {
            TerminalInterruptTrigger t = new TerminalInterruptTrigger(term);
            try {
                // 给观察线程足够时间处理完整序列；结束后仍不应取消
                Thread.sleep(400);
                assertFalse(t.isCancelled(), "方向键转义序列不应触发中断");
            } finally {
                t.close();
            }
        }
    }

    @Test
    void plainCharsDoNotCancel() throws Exception {
        try (Terminal term = terminal("hello\n".getBytes(StandardCharsets.UTF_8))) {
            TerminalInterruptTrigger t = new TerminalInterruptTrigger(term);
            try {
                Thread.sleep(300);
                assertFalse(t.isCancelled(), "普通输入不应触发中断");
            } finally {
                t.close();
            }
        }
    }

    @Test
    void closeIsIdempotentAndRestoresAttributes() throws Exception {
        try (Terminal term = terminal(new byte[]{0x1B})) {
            Attributes before = new Attributes(term.getAttributes());
            TerminalInterruptTrigger t = new TerminalInterruptTrigger(term);
            awaitCancelled(t, true, 2000);
            assertDoesNotThrow(t::close);
            assertDoesNotThrow(t::close); // 幂等
            // raw 模式被还原：ECHO 应恢复为进入前状态
            assertEquals(before.getLocalFlag(Attributes.LocalFlag.ECHO),
                    term.getAttributes().getLocalFlag(Attributes.LocalFlag.ECHO),
                    "close 后应还原终端属性");
        }
    }

    @Test
    void nullTerminalDegradesToSigintOnly() {
        TerminalInterruptTrigger t = new TerminalInterruptTrigger(null);
        assertFalse(t.isCancelled());
        assertDoesNotThrow(t::close);
        assertDoesNotThrow(t::close);
        assertFalse(t.isCancelled());
    }
}
