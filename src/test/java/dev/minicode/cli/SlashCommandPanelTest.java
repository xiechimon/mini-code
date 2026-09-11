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
}
