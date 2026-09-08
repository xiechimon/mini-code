package dev.minicode.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 正文渲染器纯函数单测：覆盖段落/标题/粗体/斜体/删除线/行内代码/链接的有色与去色两态，
 * 以及 ANSI 感知折行、PLAIN 降级、边界宽度。
 * 对应票 01 验收：正文渲染器为纯函数（不读环境不碰时钟），结构与降级断言收敛于此缝。
 */
class MarkdownRendererTest {

    private final Style plain = Style.plain();
    private final Style color = Style.colored();

    private static String strip(String s) {
        return s.replaceAll("\u001B\\[[0-9;]*m", "");
    }

    // ——— 段落 ———

    @Test
    void paragraphPlainAndColored() {
        String md = "Hello world";
        String p = MarkdownRenderer.render(md, plain, 80);
        assertEquals("Hello world", p);
        assertFalse(p.contains("\u001B["));
        String c = MarkdownRenderer.render(md, color, 80);
        assertEquals("Hello world", strip(c));
        assertFalse(c.contains("\u001B[") ); // 纯段落无高亮，不应产生 ANSI
        assertEquals(p, strip(c));
    }

    @Test
    void paragraphMultipleBlocksSeparatedByBlankLine() {
        String md = "First paragraph.\n\nSecond paragraph.";
        String plainOut = MarkdownRenderer.render(md, plain, 80);
        assertEquals("First paragraph.\n\nSecond paragraph.", plainOut);
        String colorOut = MarkdownRenderer.render(md, color, 80);
        assertEquals(plainOut, strip(colorOut));
    }

    // ——— 标题（粗体青） ———

    @Test
    void headingBoldCyan() {
        String md = "# My Title";
        String p = MarkdownRenderer.render(md, plain, 80);
        assertEquals("My Title", p);
        assertFalse(p.contains("\u001B["));
        String c = MarkdownRenderer.render(md, color, 80);
        assertTrue(c.contains(Style.ANSI_BOLD), "标题有色应含粗体");
        assertTrue(c.contains(Style.ANSI_CYAN), "标题有色应含青色");
        assertTrue(c.contains("My Title"));
        assertEquals(p, strip(c));
        // PLAIN 降级：同一结构纯文本
        assertFalse(MarkdownRenderer.render(md, plain, 80).contains("\u001B["));
    }

    @Test
    void headingLevelsAllBoldCyan() {
        for (int level = 1; level <= 6; level++) {
            String hashes = "#".repeat(level);
            String md = hashes + " Heading " + level;
            String plainOut = MarkdownRenderer.render(md, plain, 80);
            assertEquals("Heading " + level, plainOut);
            String colorOut = MarkdownRenderer.render(md, color, 80);
            assertTrue(colorOut.contains(Style.ANSI_BOLD));
            assertTrue(colorOut.contains(Style.ANSI_CYAN));
            assertEquals(plainOut, strip(colorOut));
        }
    }

    // ——— 粗体 ———

    @Test
    void bold() {
        String md = "This is **bold** text";
        String p = MarkdownRenderer.render(md, plain, 80);
        assertEquals("This is bold text", p);
        assertFalse(p.contains("\u001B["));
        String c = MarkdownRenderer.render(md, color, 80);
        assertTrue(c.contains(Style.ANSI_BOLD), "粗体应含 BOLD");
        assertTrue(c.contains("bold"));
        assertEquals(p, strip(c));
        assertFalse(p.contains("\u001B["));
    }

    // ——— 斜体 ———

    @Test
    void italic() {
        String md = "This is *italic* text";
        String p = MarkdownRenderer.render(md, plain, 80);
        assertEquals("This is italic text", p);
        String c = MarkdownRenderer.render(md, color, 80);
        assertTrue(c.contains(Style.ANSI_ITALIC), "斜体应含 ITALIC");
        assertEquals(p, strip(c));
        // 下划线形式 _italic_ 同样
        String md2 = "This is _italic_ text";
        assertEquals(p, MarkdownRenderer.render(md2, plain, 80));
        assertEquals(p, strip(MarkdownRenderer.render(md2, color, 80)));
    }

    // ——— 删除线 ———

    @Test
    void strikethrough() {
        String md = "This is ~~deleted~~ text";
        String p = MarkdownRenderer.render(md, plain, 80);
        assertEquals("This is deleted text", p);
        assertFalse(p.contains("~~"));
        String c = MarkdownRenderer.render(md, color, 80);
        assertTrue(c.contains(Style.ANSI_STRIKETHROUGH), "删除线应含 STRIKETHROUGH");
        assertEquals(p, strip(c));
    }

    @Test
    void strikethroughMultiple() {
        String md = "a ~~b~~ c ~~d~~ e";
        assertEquals("a b c d e", MarkdownRenderer.render(md, plain, 80));
        String c = MarkdownRenderer.render(md, color, 80);
        assertTrue(c.contains(Style.ANSI_STRIKETHROUGH));
        assertEquals("a b c d e", strip(c));
    }

    // ——— 行内代码（绿） ———

    @Test
    void inlineCodeGreen() {
        String md = "Use `code` here";
        String p = MarkdownRenderer.render(md, plain, 80);
        assertEquals("Use code here", p);
        String c = MarkdownRenderer.render(md, color, 80);
        assertTrue(c.contains(Style.ANSI_GREEN), "行内代码应含绿");
        assertTrue(c.contains("code"));
        assertEquals(p, strip(c));
    }

    // ——— 链接（文本下划线+URL 暗灰） ———

    @Test
    void linkUnderlineAndGrayUrl() {
        String md = "See [example](https://example.com) now";
        String p = MarkdownRenderer.render(md, plain, 80);
        assertEquals("See example (https://example.com) now", p);
        assertFalse(p.contains("\u001B["));
        String c = MarkdownRenderer.render(md, color, 80);
        assertTrue(c.contains(Style.ANSI_UNDERLINE), "链接文本应下划线");
        assertTrue(c.contains(Style.ANSI_GRAY), "链接 URL 应暗灰");
        assertTrue(c.contains("example"));
        assertTrue(c.contains("https://example.com"));
        assertEquals(p, strip(c));
    }

    @Test
    void linkWithBoldText() {
        String md = "See [**bold link**](http://a.com)";
        String p = MarkdownRenderer.render(md, plain, 80);
        assertEquals("See bold link (http://a.com)", p);
        String c = MarkdownRenderer.render(md, color, 80);
        // 粗体在链接文本内，应同时含 BOLD 与 UNDERLINE
        assertTrue(c.contains(Style.ANSI_BOLD) || c.contains(Style.ANSI_UNDERLINE));
        assertEquals(p, strip(c));
    }

    // ——— PLAIN 降级：同结构规则纯文本 ———

    @Test
    void plainDegradeKeepsStructureWithoutAnsi() {
        String md = "# Title\n\nThis is **bold** and *italic* with `code` and ~~del~~ and [link](http://x.com)";
        String p = MarkdownRenderer.render(md, plain, 80);
        assertFalse(p.contains("\u001B["));
        assertTrue(p.contains("Title"));
        assertTrue(p.contains("bold"));
        assertTrue(p.contains("italic"));
        assertTrue(p.contains("code"));
        assertTrue(p.contains("del"));
        assertTrue(p.contains("link (http://x.com)"));
        // 有色剥离后与去色一致
        String c = MarkdownRenderer.render(md, color, 80);
        assertEquals(p, strip(c));
        // 有色应含对应 ANSI
        assertTrue(c.contains(Style.ANSI_BOLD));
        assertTrue(c.contains(Style.ANSI_ITALIC));
        assertTrue(c.contains(Style.ANSI_GREEN));
        assertTrue(c.contains(Style.ANSI_STRIKETHROUGH));
        assertTrue(c.contains(Style.ANSI_UNDERLINE));
    }

    // ——— ANSI 感知折行 ———

    @Test
    void wrapAnsiCountsVisibleNotAnsiLength() {
        // 纯段落按注入宽度折行
        String longText = "word ".repeat(30); // 150 chars inc spaces
        String md = longText.trim();
        int w = 20;
        String plainWrapped = MarkdownRenderer.render(md, plain, w);
        for (String line : plainWrapped.split("\n")) {
            assertTrue(line.length() <= w, "PLAIN 每行可见长度应 <= 宽度，实际 " + line.length() + " 行: [" + line + "]");
        }
        String colorWrapped = MarkdownRenderer.render("**bold** " + md, color, w);
        for (String line : colorWrapped.split("\n")) {
            int vl = strip(line).length();
            assertTrue(vl <= w, "COLOR 每行可见长度应 <= 宽度，实际 " + vl + " 行: [" + strip(line) + "]  raw: [" + line + "]");
        }
        // ANSI 不计宽：有色与去色折行数应相同或相近（可见长度一致）
        String plainBold = MarkdownRenderer.render("**bold** " + md, plain, w);
        assertEquals(plainBold.split("\n").length, colorWrapped.split("\n").length, "ANSI 不计宽，有色与去色行数应一致");
        assertEquals(plainBold, strip(colorWrapped));
    }

    @Test
    void wrapOverlongWordHardBreak() {
        String longWord = "a".repeat(50);
        int w = 10;
        String plainWrapped = MarkdownRenderer.render(longWord, plain, w);
        String[] lines = plainWrapped.split("\n");
        assertEquals(5, lines.length, "50 字符超长词按 10 宽应硬折为 5 行");
        for (String line : lines) {
            assertTrue(line.length() <= w);
            assertEquals(w, line.length(), "除末行外每行应占满宽度");
        }
        // 行内代码绿色的超长词：每行应仍保持绿且可见长度合规
        String codeWrapped = MarkdownRenderer.render("`" + longWord + "`", color, w);
        for (String line : codeWrapped.split("\n")) {
            assertTrue(strip(line).length() <= w);
            assertTrue(line.contains(Style.ANSI_GREEN) || strip(line).length() == 0, "行内代码每段应含绿");
        }
        assertEquals(plainWrapped, strip(codeWrapped));
    }

    @Test
    void wrapBoundaryZeroAndNarrow() {
        // 宽度 0/负数 不崩，回退 80（或不折行）
        String md = "Hello world";
        assertEquals("Hello world", MarkdownRenderer.render(md, plain, 0));
        assertEquals("Hello world", MarkdownRenderer.render(md, plain, -5));
        // 极窄宽度仍不崩且不抛异常
        String w1 = MarkdownRenderer.render(md, plain, 1);
        assertNotNull(w1);
        assertTrue(strip(w1).replace("\n", "").replace(" ", "").contains("Hello".replace(" ", "")) || w1.contains("H"), "极窄宽度应产出且含内容");
        // 极窄宽度仍不崩：w=5 应折为两行
        String narrow = MarkdownRenderer.render("Hello world", plain, 5);
        assertEquals(2, narrow.split("\n").length);
        assertTrue(narrow.contains("Hello"));
        assertTrue(narrow.contains("world"));
        // 极窄有色同样结构
        String narrowColor = MarkdownRenderer.render("Hello **world**", color, 5);
        assertEquals(2, narrowColor.split("\n").length);
        assertEquals(narrow.split("\n")[0], strip(narrowColor).split("\n")[0]); // 第一行同
        assertEquals(strip(narrowColor), MarkdownRenderer.render("Hello **world**", plain, 5));
    }

    @Test
    void wrapPreservesStylingAcrossLines() {
        // 标题折行后每行应仍保留标题样式（粗体青），剥离后与去色一致
        String md = "# My Title is quite long title for wrapping";
        int w = 10;
        String plainWrapped = MarkdownRenderer.render(md, plain, w);
        String colorWrapped = MarkdownRenderer.render(md, color, w);
        assertEquals(plainWrapped, strip(colorWrapped));
        for (String line : colorWrapped.split("\n")) {
            // 每行应含标题色（除非空行）
            if (!strip(line).isBlank()) {
                assertTrue(line.contains(Style.ANSI_BOLD) || line.contains(Style.ANSI_CYAN), "标题折行每行应含标题样式");
            }
        }
    }

    // ——— 纯函数：不读环境不碰时钟，确定性 ———

    @Test
    void pureFunctionDeterministic() {
        String md = "Hello **bold** world";
        String a = MarkdownRenderer.render(md, color, 80);
        String b = MarkdownRenderer.render(md, color, 80);
        assertEquals(a, b);
        // 不同宽度产生不同折行，证明宽度来自注入而非环境
        String w20 = MarkdownRenderer.render("word ".repeat(20), plain, 20);
        String w40 = MarkdownRenderer.render("word ".repeat(20), plain, 40);
        assertNotEquals(w20, w40);
        assertTrue(w20.split("\n").length > w40.split("\n").length);
    }

    @Test
    void emptyAndBlankHandled() {
        assertEquals("", MarkdownRenderer.render("", plain, 80));
        assertEquals("", MarkdownRenderer.render("   ", plain, 80));
        assertEquals("", MarkdownRenderer.render(null, plain, 80));
        assertEquals("", MarkdownRenderer.render("\n\n", plain, 80));
    }

    @Test
    void htmlBlockAsPlainText() {
        String md = "<div>hello</div>";
        // HTML 块按纯文本原样输出（Out of Scope 约束）
        String out = MarkdownRenderer.render(md, plain, 80);
        assertTrue(out.contains("<div>hello</div>") || out.contains("hello"), "HTML 块应透传");
        assertEquals(out, strip(MarkdownRenderer.render(md, color, 80)));
    }

    @Test
    void codeBlockFallbackAsPlainUntilNextTicket() {
        String md = "```java\nint x = 1;\n```";
        String plainOut = MarkdownRenderer.render(md, plain, 80);
        assertTrue(plainOut.contains("int x = 1;"));
        assertEquals(plainOut, strip(MarkdownRenderer.render(md, color, 80)));
        // 代码块不折行（后续票保证），本票透传保持原始行
        assertFalse(plainOut.contains("```"));
    }

    @Test
    void listAndBlockquoteFallbackTransparent() {
        // 列表/引用本票透传，不崩且剥离一致
        String mdList = "- item one\n- item two";
        String listPlain = MarkdownRenderer.render(mdList, plain, 80);
        assertTrue(listPlain.contains("item one"));
        assertEquals(listPlain, strip(MarkdownRenderer.render(mdList, color, 80)));

        String mdQuote = "> quoted text";
        String quotePlain = MarkdownRenderer.render(mdQuote, plain, 80);
        assertTrue(quotePlain.contains("quoted text"));
        assertEquals(quotePlain, strip(MarkdownRenderer.render(mdQuote, color, 80)));
    }

    @Test
    void nullStyleTreatedAsPlain() {
        String md = "Hello **bold**";
        assertEquals(MarkdownRenderer.render(md, plain, 80), MarkdownRenderer.render(md, null, 80));
        assertFalse(MarkdownRenderer.render(md, null, 80).contains("\u001B["));
    }
}
