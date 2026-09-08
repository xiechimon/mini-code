package dev.minicode.cli;

import dev.minicode.agent.AgentEvent;
import dev.minicode.ai.Message;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 事件渲染接线断言：助手文本经正文渲染器（标题/粗体/链接等样式），
 * 工具结果/轮首/横幅/统计路径零回归；宽度注入（REPL 终端宽度、管道 80）。
 */
class EventRendererMarkdownTest {

    private final Style plain = Style.plain();
    private final Style color = Style.colored();

    private static String strip(String s) {
        return s.replaceAll("\u001B\\[[0-9;]*m", "");
    }

    @Test
    void assistantTextGoesThroughMarkdownRendererColored() {
        // 标题 + 粗体的助手回复，应经 Markdown 渲染为有色
        String md = "# Title\n\nThis is **bold**";
        Message msg = Message.assistant(List.of(Message.Content.text(md)), "end");
        AgentEvent e = new AgentEvent.MessageEnd(msg);
        String plainOut = EventRenderer.render(e, plain, 80);
        // PLAIN：标题与粗体剥离为纯文本，保留结构
        assertTrue(plainOut.contains("Title"), "PLAIN 应含标题文本");
        assertTrue(plainOut.contains("bold"), "PLAIN 应含粗体文本");
        assertFalse(plainOut.contains("\u001B["));
        assertFalse(plainOut.contains("#"));
        assertFalse(plainOut.contains("**"));

        String colorOut = EventRenderer.render(e, color, 80);
        assertTrue(colorOut.contains(Style.ANSI_BOLD), "有色标题/粗体应含 BOLD");
        assertTrue(colorOut.contains(Style.ANSI_CYAN), "标题应含 CYAN");
        assertTrue(colorOut.contains("Title"));
        assertTrue(colorOut.contains("bold"));
        assertEquals(plainOut, strip(colorOut));
    }

    @Test
    void assistantInlineStylesViaMarkdown() {
        String md = "Use `code` and [link](http://x.com) and *italic* and ~~del~~";
        Message msg = Message.assistant(List.of(Message.Content.text(md)), "end");
        AgentEvent e = new AgentEvent.MessageEnd(msg);
        String plainOut = EventRenderer.render(e, plain, 80);
        assertTrue(plainOut.contains("code"));
        assertTrue(plainOut.contains("link (http://x.com)"));
        assertTrue(plainOut.contains("italic"));
        assertTrue(plainOut.contains("del"));
        assertFalse(plainOut.contains("`"));
        assertFalse(plainOut.contains("~~"));

        String colorOut = EventRenderer.render(e, color, 80);
        assertTrue(colorOut.contains(Style.ANSI_GREEN), "行内代码绿");
        assertTrue(colorOut.contains(Style.ANSI_UNDERLINE), "链接下划线");
        assertTrue(colorOut.contains(Style.ANSI_GRAY), "链接 URL 暗灰");
        assertTrue(colorOut.contains(Style.ANSI_ITALIC), "斜体");
        assertTrue(colorOut.contains(Style.ANSI_STRIKETHROUGH), "删除线");
        assertEquals(plainOut, strip(colorOut));
    }

    @Test
    void assistantTextAndToolCallsCombinedMarkdown() {
        String md = "Hello **bold**";
        Message.ToolCall tc = new Message.ToolCall("1", "read", Map.of("path", "a.txt"), "{\"path\":\"a.txt\"}");
        Message msg = Message.assistant(List.of(Message.Content.text(md), Message.Content.toolCall(tc)), "toolCalls");
        AgentEvent e = new AgentEvent.MessageEnd(msg);
        String plainOut = EventRenderer.render(e, plain, 80);
        assertTrue(plainOut.contains("Hello bold"), "正文应经 Markdown 剥离为纯文本");
        assertTrue(plainOut.contains("→ read a.txt"), "工具调用行保持");
        assertFalse(plainOut.contains("**"));

        String colorOut = EventRenderer.render(e, color, 80);
        assertTrue(colorOut.contains(Style.ANSI_BOLD), "正文粗体应有色");
        assertTrue(colorOut.contains(Style.ANSI_CYAN), "工具名青");
        assertEquals(plainOut, strip(colorOut));
    }

    @Test
    void toolResultPathNotRegressed() {
        Message.ToolCall tc = new Message.ToolCall("1", "read", Map.of("path", "a.txt"), "{}");
        AgentEvent succ = new AgentEvent.ToolResultEvent(tc, "line1\nline2", false);
        String plainSucc = EventRenderer.render(succ, plain, 80);
        assertEquals("← read: line1 … 共 2 行", plainSucc);
        String colorSucc = EventRenderer.render(succ, color, 80);
        assertTrue(colorSucc.contains(Style.ANSI_GREEN));
        assertTrue(colorSucc.contains(Style.ANSI_GRAY));
        assertEquals(plainSucc, strip(colorSucc));

        AgentEvent fail = new AgentEvent.ToolResultEvent(tc, "boom", true);
        String plainFail = EventRenderer.render(fail, plain, 80);
        assertEquals("← read [失败]: boom", plainFail);
        assertTrue(EventRenderer.render(fail, color, 80).contains(Style.ANSI_RED));
    }

    @Test
    void turnStartBannerStatsNotRegressed() {
        AgentEvent turn = new AgentEvent.TurnStart(1);
        assertEquals("\n[第 1 轮] 思考中...", EventRenderer.render(turn, plain, 80));
        assertTrue(EventRenderer.render(turn, color, 80).contains(Style.ANSI_GRAY));
        assertEquals(EventRenderer.render(turn, plain, 80), strip(EventRenderer.render(turn, color, 80)));

        Path workdir = Path.of("/tmp/work");
        String bannerPlain = EventRenderer.renderBanner("opencode-go", "kimi-k2.6", workdir, plain);
        String bannerColor = EventRenderer.renderBanner("opencode-go", "kimi-k2.6", workdir, color);
        assertEquals(bannerPlain, strip(bannerColor));

        AgentEvent end = new AgentEvent.AgentEnd(List.of());
        assertEquals("\n1 轮 · 2 次工具 · 0.5s", EventRenderer.render(end, plain, Duration.ofMillis(500), 1, 2, 80));
        assertEquals("\n1 轮 · 2 次工具 · 0.5s", strip(EventRenderer.render(end, color, Duration.ofMillis(500), 1, 2, 80)));
    }

    @Test
    void widthInjectionReplVsPipe() {
        String md = "word ".repeat(30);
        Message msg = Message.assistant(List.of(Message.Content.text(md)), "end");
        AgentEvent e = new AgentEvent.MessageEnd(msg);
        // 管道 80 与 窄 REPL 宽度产生不同折行
        String wide = EventRenderer.render(e, plain, 80);
        String narrow = EventRenderer.render(e, plain, 20);
        assertNotEquals(wide, narrow, "不同宽度应产生不同折行");
        assertTrue(narrow.split("\n").length > wide.split("\n").length);
        // 有色同样
        String wideColor = EventRenderer.render(e, color, 80);
        String narrowColor = EventRenderer.render(e, color, 20);
        assertEquals(wide, strip(wideColor));
        assertEquals(narrow, strip(narrowColor));
        // 默认宽度（不传 width）应等同 80（管道语义）
        assertEquals(wide, EventRenderer.render(e, plain));
        assertEquals(wide, strip(EventRenderer.render(e, color)));
    }

    @Test
    void widthInjectionHelperTerminalWidth() throws Exception {
        // 终端宽度注入：Main.terminalWidth 应对有效终端返回正数，null/零宽回退 80
        assertEquals(80, Main.terminalWidth(null), "null 终端回退 80");
        // 用 ExternalTerminal 模拟宽度 100
        org.jline.terminal.Terminal t = new org.jline.terminal.impl.ExternalTerminal("test-w", "xterm",
                new java.io.ByteArrayInputStream(new byte[0]),
                new java.io.ByteArrayOutputStream(), java.nio.charset.StandardCharsets.UTF_8);
        // ExternalTerminal 默认宽度可能为 0，需通过 setSize 设置
        t.setSize(new org.jline.terminal.Size(100, 24));
        assertEquals(100, Main.terminalWidth(t), "设置宽度后应返回 100");
        t.close();
    }

    @Test
    void eventRendererDefaultWidthIs80() {
        String md = "word ".repeat(30);
        Message msg = Message.assistant(List.of(Message.Content.text(md)), "end");
        AgentEvent e = new AgentEvent.MessageEnd(msg);
        String viaDefault = EventRenderer.render(e, plain);
        String via80 = EventRenderer.render(e, plain, 80);
        assertEquals(via80, viaDefault, "默认宽度应为 80，兼容管道语义");
    }
}
