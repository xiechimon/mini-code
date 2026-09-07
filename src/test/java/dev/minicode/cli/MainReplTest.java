package dev.minicode.cli;

import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.jline.terminal.impl.ExternalTerminal;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * REPL 输入层单测。
 * 背景：交互式 REPL 曾用 Scanner 直读 stdin，方向键转义序列(ESC [ D)被当成普通字符，
 * 终端原样显示为 ^[[D。修复为 JLine 行编辑后补回归单测。
 */
class MainReplTest {

    @Test
    void exitCommands() {
        assertTrue(Main.isExitCommand("exit"));
        assertTrue(Main.isExitCommand("EXIT"));
        assertTrue(Main.isExitCommand("quit"));
        assertTrue(Main.isExitCommand("/exit"));
        assertFalse(Main.isExitCommand("exits"));
        assertFalse(Main.isExitCommand("帮我退出吗"));
    }

    @Test
    void filterPipeLinesSkipsBlanksAndStopsAtExit() {
        List<String> out = Main.filterPipeLines(List.of("  ", "第一问", "", "第二问", "exit", "第三问"));
        assertEquals(List.of("第一问", "第二问"), out);
    }

    @Test
    void filterPipeLinesEmpty() {
        assertTrue(Main.filterPipeLines(List.of("  ", "")).isEmpty());
        assertTrue(Main.filterPipeLines(List.of("quit")).isEmpty());
    }

    /**
     * 旧路径留档：Scanner 会把方向键转义序列当普通字符读入（长度 6 而非编辑后的效果）。
     * 该测试锁定“为什么不能用 Scanner 做交互输入”，防止回退。
     */
    @Test
    void scannerPassesThroughArrowEscapeSequence() {
        byte[] input = new byte[]{'a', 'b', 0x1B, '[', 'D', 'X', '\n'};
        java.util.Scanner scanner = new java.util.Scanner(
                new ByteArrayInputStream(input), StandardCharsets.UTF_8);
        String line = scanner.nextLine();
        assertTrue(line.contains("\u001B[D"), "Scanner 应原样透出转义序列（这正是 ^[[D 的来源）");
        assertEquals(6, line.length());
    }

    /**
     * 新路径冒烟：JLine 接线在无 TTY(dumb)环境下可正常读取整行（方向键编辑需真实终端，此处仅验接线）。
     * 注意用 ExternalTerminal 而非 TerminalBuilder：后者 3.27.1 会尝试建 pty，在测试 JVM 中不稳定。
     */
    @Test
    void jlineReaderReadsLineHeadless() throws Exception {
        byte[] input = "你好 mini-code\n".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Terminal terminal = new ExternalTerminal("mini-test", "dumb",
                new ByteArrayInputStream(input), output, StandardCharsets.UTF_8)) {
            LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();
            assertEquals("你好 mini-code", reader.readLine(""));
        }
    }
}
