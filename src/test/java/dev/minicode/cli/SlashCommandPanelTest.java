package dev.minicode.cli;

import org.jline.reader.LineReader;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.terminal.Terminal;
import org.jline.terminal.impl.ExternalTerminal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 命令面板 widget 壳的接线冒烟（ExternalTerminal，先例 MainReplTest）。
 * 只测接线可还原性：enable/disable 往返后键表别名、Esc 绑定、消歧超时、建议类型全部还原；
 * 不渲染断言（dumb 终端 Status 无操作 = 降级即现状，由 PanelModelTest 覆盖行为面）。
 */
class SlashCommandPanelTest {

    @TempDir
    Path tmp;

    private LineReader newDumbReader() throws Exception {
        Terminal t = new ExternalTerminal("panel-test", "dumb",
                new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream(), StandardCharsets.UTF_8);
        return Main.createReader(t, tmp.resolve("h"), SlashCommands.commandCompleter(null));
    }

    @Test
    void enableDisableRestoresKeymapAndSettings() throws Exception {
        LineReader reader = newDumbReader();
        // 前置：createReader 装了命令补全后建议类型为 COMPLETER
        assertEquals(LineReader.SuggestionType.COMPLETER, reader.getAutosuggestion());
        Object ambiguousBefore = reader.getVariable(LineReader.AMBIGUOUS_BINDING);

        SlashCommandPanel panel = new SlashCommandPanel(reader, null);
        panel.enable();
        assertTrue(panel.isEnabled());
        // 别名生效：内建名现在解析到 _panel 变体
        assertEquals("_panel-accept-line", reader.getWidgets().get(LineReader.ACCEPT_LINE).toString());
        assertEquals("_panel-up", reader.getWidgets().get(LineReader.UP_LINE_OR_SEARCH).toString());
        // Esc 已绑定；建议列表被面板取代
        assertNotNull(reader.getKeyMaps().get(LineReaderImpl.MAIN).getBound("\u001b"));
        assertEquals(LineReader.SuggestionType.NONE, reader.getAutosuggestion());
        assertEquals(100L, reader.getVariable(LineReader.AMBIGUOUS_BINDING));

        panel.disable();
        assertFalse(panel.isEnabled());
        // 全部还原
        assertNotEquals("_panel-accept-line", reader.getWidgets().get(LineReader.ACCEPT_LINE).toString());
        assertNull(reader.getKeyMaps().get(LineReaderImpl.MAIN).getBound("\u001b"), "Esc 应解绑");
        assertEquals(LineReader.SuggestionType.COMPLETER, reader.getAutosuggestion());
        assertEquals(ambiguousBefore, reader.getVariable(LineReader.AMBIGUOUS_BINDING));
        reader.getTerminal().close();
    }

    @Test
    void dumbTerminalEnableDoesNotThrow() throws Exception {
        LineReader reader = newDumbReader();
        SlashCommandPanel panel = new SlashCommandPanel(reader, null);
        panel.enable();
        // dumb 终端 Status 无操作：面板渲染静默降级
        panel.model().onBufferChanged("/he");
        assertTrue(panel.model().isOpen(), "状态机照常工作（渲染降级由 Status 承担）");
        panel.disable();
        reader.getTerminal().close();
    }

    @Test
    void doubleEnableDisableIsIdempotent() throws Exception {
        LineReader reader = newDumbReader();
        SlashCommandPanel panel = new SlashCommandPanel(reader, null);
        panel.enable();
        panel.enable();
        panel.disable();
        panel.disable();
        assertFalse(panel.isEnabled());
        reader.getTerminal().close();
    }

    /**
     * 击键级回归（用户两个症状：双显 + 漂移）：xterm 能力的 ExternalTerminal + 管道输入驱动真实 readLine。
     * ① 输 / 面板渲染（含 /help）且内建列表（括号描述签名）不同屏；② 面板开着时 Tab = 面板内下移；
     * ③ 固定高度：多轮开闭后滚动区序列至多出现一次（初始预占）——变高即漂移的回归锁；
     * ④ 无参命令 Enter 选中即执行。
     */
    @Test
    void panelRendersFiltersAndNeverDrifts() throws Exception {
        java.io.PipedInputStream termIn = new java.io.PipedInputStream();
        java.io.PipedOutputStream keys = new java.io.PipedOutputStream(termIn);
        ByteArrayOutputStream termOut = new ByteArrayOutputStream();
        try (Terminal t = new ExternalTerminal("panel-it", "xterm", termIn, termOut, StandardCharsets.UTF_8)) {
            t.setSize(new org.jline.terminal.Size(120, 30));
            LineReader reader = Main.createReader(t, tmp.resolve("h2"), SlashCommands.commandCompleter(null));
            SlashCommandPanel panel = new SlashCommandPanel(reader, null);
            panel.enable();

            java.util.concurrent.atomic.AtomicReference<String> line = new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicReference<Throwable> err = new java.util.concurrent.atomic.AtomicReference<>();
            Thread th = new Thread(() -> {
                try {
                    line.set(reader.readLine("❯ "));
                } catch (Throwable e) {
                    err.set(e);
                }
            });
            th.setDaemon(true);
            th.start();
            Thread.sleep(300);

            // ① 输 /：面板打开（含 /help），内建列表不得同屏
            keys.write("/".getBytes(StandardCharsets.UTF_8));
            keys.flush();
            String screen = pollUntil(termOut, "/help", 2000);
            assertTrue(screen.contains("/help"), "输 / 后应渲染面板，实际输出长度=" + screen.length());
            assertFalse(screen.contains("(退出 REPL)"), "面板打开时内建建议列表不得同屏");

            // ② 面板开着时 Tab = 面板内下移（不弹内建列表）
            int beforeTab = termOut.toString(StandardCharsets.UTF_8).length(); // 按字符数（CJK 多字节，size() 是字节数会错位）
            keys.write("\t".getBytes(StandardCharsets.UTF_8));
            keys.flush();
            Thread.sleep(400);
            String afterTab = termOut.toString(StandardCharsets.UTF_8).substring(beforeTab);
            assertFalse(afterTab.contains("(退出 REPL)"), "面板打开时 Tab 不得弹内建补全列表");
            assertEquals(1, panel.model().selectedIndex(), "Tab 应在面板内下移高亮");

            // ③ 两轮 开→Esc 关→Ctrl-U 清行→再开：固定高度下滚动区不得重算
            // （基准取在首个面板稳定渲染后：enable 预占与 readLine 启动各有一次合法变更）
            int beforeCycles = termOut.toString(StandardCharsets.UTF_8).length();
            for (int i = 0; i < 2; i++) {
                keys.write(0x1B);
                keys.flush();
                Thread.sleep(400);
                keys.write(0x15);
                keys.flush();
                Thread.sleep(200);
                keys.write("/".getBytes(StandardCharsets.UTF_8));
                keys.flush();
                Thread.sleep(300);
            }
            keys.write(0x1B);
            keys.flush();
            Thread.sleep(300);

            String cyclesOut = termOut.toString(StandardCharsets.UTF_8).substring(beforeCycles);
            long regionChanges = java.util.regex.Pattern.compile("\u001b" + "\\[\\d+;\\d+r").matcher(cyclesOut).results().count();
            assertEquals(0, regionChanges,
                    "面板开闭循环期间不得重算滚动区（每次重算=内容上推一段，漂移根源）");

            // ④ Enter 选中无参命令（/help）直接执行
            keys.write(0x15);
            keys.write("/".getBytes(StandardCharsets.UTF_8));
            keys.flush();
            Thread.sleep(300);
            keys.write("\r".getBytes(StandardCharsets.UTF_8));
            keys.flush();
            th.join(3000);
            assertNull(err.get(), err.get() == null ? "" : err.get().toString());
            assertEquals("/help", line.get(), "无参命令选中应填入并直接执行");
            panel.disable();
        }
    }

    private static String pollUntil(ByteArrayOutputStream out, String marker, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String s = "";
        while (System.currentTimeMillis() < deadline) {
            s = out.toString(StandardCharsets.UTF_8);
            if (s.contains(marker)) {
                return s;
            }
            Thread.sleep(50);
        }
        return s;
    }
}
