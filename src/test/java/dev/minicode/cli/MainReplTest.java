package dev.minicode.cli;

import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.jline.terminal.impl.ExternalTerminal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
        assertTrue(Main.isExitCommand("/quit"));
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
     * 管道透传锁定：斜杠命令属交互编辑器层概念（对齐 pi），管道/批处理输入中的 /xxx 行
     * 原样发给 LLM，不解析、不拦截；遇退出命令仍截断。见 .scratch/slash-commands/spec.md。
     */
    @Test
    void filterPipeLinesPassesSlashCommandsThrough() {
        List<String> out = Main.filterPipeLines(List.of("/compact", "正常问题", "/quit", "不应到达"));
        assertEquals(List.of("/compact", "正常问题"), out);
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

    /**
     * 命令自动提示接线回归：createReader 带 completer 时开启 SuggestionType.COMPLETER
     * （输 / 即出列表，对齐 pi），无 completer 时保持 NONE。dumb 终端下 JLine 内部降级，
     * 此处仅锁定开关接线不回退。
     */
    @Test
    void readerEnablesAutosuggestionWhenCompleterPresent(@TempDir Path tmp) throws Exception {
        try (Terminal t = new ExternalTerminal("test-as", "dumb",
                new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream(), StandardCharsets.UTF_8)) {
            LineReader with = Main.createReader(t, tmp.resolve("h1"), SlashCommands.commandCompleter(null));
            assertEquals(LineReader.SuggestionType.COMPLETER, with.getAutosuggestion());
            LineReader without = Main.createReader(t, tmp.resolve("h2"), (org.jline.reader.Completer) null);
            assertEquals(LineReader.SuggestionType.NONE, without.getAutosuggestion());
        }
    }

    /**
     * 历史落盘单测：按规格 Testing Decisions，用 @TempDir 注入 history 路径——
     * 先建 reader 写入一行输入触发 save()，再建新 reader（同路径）断言历史可翻到该行。
     * 测试缝走 Main.createReader / ExternalTerminal dumb。
     */
    @Test
    void historyPersistsAcrossReaders(@TempDir Path tmp) throws Exception {
        Path hist = tmp.resolve("history");
        // 首个 reader：输入一行触发历史添加与落盘
        byte[] input = "hello persistent\n".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out1 = new ByteArrayOutputStream();
        try (Terminal t1 = new ExternalTerminal("test-history-1", "dumb",
                new ByteArrayInputStream(input), out1, StandardCharsets.UTF_8)) {
            LineReader r1 = Main.createReader(t1, hist);
            String line = r1.readLine("");
            assertEquals("hello persistent", line);
            // 显式 save 兜底，确保 @TempDir 路径下文件可见（runJLineRepl 同款逻辑）
            r1.getHistory().save();
            assertTrue(Files.exists(hist), "历史文件应已落盘");
        }
        // 新 reader 同路径应可翻到该行（history attach 后加载）
        ByteArrayOutputStream out2 = new ByteArrayOutputStream();
        try (Terminal t2 = new ExternalTerminal("test-history-2", "dumb",
                new ByteArrayInputStream(new byte[0]), out2, StandardCharsets.UTF_8)) {
            LineReader r2 = Main.createReader(t2, hist);
            // history 在首次 readLine/attach 时加载；此处显式 attach 以触发 load
            r2.getHistory().attach(r2);
            boolean found = false;
            for (var e : r2.getHistory()) {
                if ("hello persistent".equals(e.line())) {
                    found = true;
                    break;
                }
            }
            assertTrue(found, "新 reader 历史应可翻到上次输入");
            assertEquals(1, r2.getHistory().size());
        }
    }
}
