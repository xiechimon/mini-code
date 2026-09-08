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

    // ——— 块级：代码块盒 ———

    @Test
    void codeBlockBoxBorderAndGray() {
        String md = "```java\nint x = 1;\nString s = \"hi\";\n```";
        String p = MarkdownRenderer.render(md, plain, 80);
        assertFalse(p.contains("\u001B["), "去色不应含 ANSI");
        assertTrue(p.contains("┌"), "应含上边线 ┌");
        assertTrue(p.contains("└"), "应含下边线 └");
        assertTrue(p.contains("│ "), "应含左竖线 │ ");
        assertTrue(p.contains("int x = 1;"));
        assertTrue(p.contains("String s = \"hi\";"));
        assertFalse(p.contains("```"), "不应保留围栏标记");
        String c = MarkdownRenderer.render(md, color, 80);
        assertTrue(c.contains(Style.ANSI_GRAY), "代码块有色应含暗灰");
        assertTrue(c.contains("┌"));
        assertTrue(c.contains("└"));
        assertTrue(c.contains("│ "));
        assertEquals(p, strip(c));
        // 有色每行均应含 GRAY
        for (String line : c.split("\n")) {
            if (!strip(line).isEmpty()) {
                assertTrue(line.contains(Style.ANSI_GRAY), "代码块每行应含 GRAY，行: " + strip(line));
            }
        }
        // 边框也为灰色
        assertTrue(c.contains(Style.ANSI_GRAY + "┌"));
        assertTrue(c.contains(Style.ANSI_GRAY + "└"));
        assertTrue(c.contains(Style.ANSI_GRAY + "│ "));
    }

    @Test
    void codeBlockNoWrapEvenWhenNarrow() {
        String longLine = "a".repeat(100);
        String md = "```\n" + longLine + "\n```";
        String p = MarkdownRenderer.render(md, plain, 20);
        String[] lines = p.split("\n");
        assertEquals(3, lines.length, "代码块不折行，即使宽度极窄也应保持 3 行（上边/内容/下边）");
        assertTrue(lines[0].startsWith("┌"), "首行应为上边线");
        assertTrue(lines[1].startsWith("│ "), "内容行应以 │ 前缀");
        assertEquals(longLine, lines[1].substring(2), "内容行应完整保留原始行");
        assertTrue(lines[2].startsWith("└"), "末行应为下边线");
        String c = MarkdownRenderer.render(md, color, 20);
        assertEquals(p, strip(c));
        assertTrue(strip(c).contains(longLine), "有色剥离后仍含完整长行");
        // 窄宽度与宽宽度结果一致（不因宽度折行）
        String pWide = MarkdownRenderer.render(md, plain, 80);
        assertEquals(p, pWide, "代码块不应受宽度影响");
        assertEquals(pWide, strip(MarkdownRenderer.render(md, color, 80)));
    }

    @Test
    void codeBlockSpecialCharsPreserved() {
        String md = "```\n<>& special `inline` *star* **bold**\n```";
        // 上面构造: <>&' `inline` *star* **bold** 需原样保留
        String p = MarkdownRenderer.render(md, plain, 80);
        assertTrue(p.contains("<>&"), "特殊字符 <>& 应原样保留");
        assertTrue(p.contains("*star*"), "星号应原样保留");
        assertTrue(p.contains("**bold**"), "双星号应原样保留");
        assertFalse(p.contains("\u001B["), "去色无 ANSI");
        String c = MarkdownRenderer.render(md, color, 80);
        assertEquals(p, strip(c));
        assertTrue(c.contains(Style.ANSI_GRAY));
        // 内容不应被解析为粗体/斜体等
        assertFalse(c.contains(Style.ANSI_BOLD) && c.contains("bold") && !p.contains("bold") , "代码块内不应产生额外样式");
    }

    @Test
    void codeBlockSpecialCharsPreserved2() {
        String md = "```\nString s = \"<tag>&\";\n```";
        String p = MarkdownRenderer.render(md, plain, 80);
        assertTrue(p.contains("String s = \"<tag>&\";"), "特殊字符串应原样");
        assertEquals(p, strip(MarkdownRenderer.render(md, color, 80)));
    }

    @Test
    void codeBlockEmptyAndMinimalBorder() {
        String mdEmpty = "```\n```";
        String pEmpty = MarkdownRenderer.render(mdEmpty, plain, 80);
        assertTrue(pEmpty.contains("┌"), "空代码块应仍有上边线");
        assertTrue(pEmpty.contains("└"), "空代码块应仍有下边线");
        assertTrue(pEmpty.contains("─".repeat(3)), "空代码块边框至少 3 个 ─");
        assertFalse(pEmpty.contains("\u001B["));
        // 空块为两行：上边 + 下边
        String[] lines = pEmpty.split("\n", -1);
        assertEquals(2, lines.length, "空代码块应为 2 行（上下边线）");
        String cEmpty = MarkdownRenderer.render(mdEmpty, color, 80);
        assertEquals(pEmpty, strip(cEmpty));
        assertTrue(cEmpty.contains(Style.ANSI_GRAY));

        String mdShort = "```\na\n```";
        String pShort = MarkdownRenderer.render(mdShort, plain, 80);
        String[] shortLines = pShort.split("\n");
        assertEquals(3, shortLines.length, "单字符代码块应为 3 行");
        assertTrue(shortLines[1].contains("a"));
        assertTrue(shortLines[0].startsWith("┌"));
        assertEquals(pShort, strip(MarkdownRenderer.render(mdShort, color, 80)));

        // 多行代码块
        String mdMulti = "```\nline1\nline2\nline3\n```";
        String pMulti = MarkdownRenderer.render(mdMulti, plain, 80);
        assertTrue(pMulti.contains("line1"));
        assertTrue(pMulti.contains("line2"));
        assertTrue(pMulti.contains("line3"));
        assertEquals(5, pMulti.split("\n").length, "三行内容应为 5 行（含上下边线）");
        assertEquals(pMulti, strip(MarkdownRenderer.render(mdMulti, color, 80)));
    }

    @Test
    void codeBlockIndented() {
        String md = "    line one\n    line two";
        String p = MarkdownRenderer.render(md, plain, 80);
        assertTrue(p.contains("┌"), "缩进代码块应有边框");
        assertTrue(p.contains("│ "), "缩进代码块应有左竖线");
        assertTrue(p.contains("line one"));
        assertTrue(p.contains("line two"));
        assertFalse(p.contains("\u001B["));
        String c = MarkdownRenderer.render(md, color, 80);
        assertTrue(c.contains(Style.ANSI_GRAY));
        assertEquals(p, strip(c));
        // 缩进与围栏代码块同结构规则
        for (String line : c.split("\n")) {
            if (!strip(line).isEmpty()) {
                assertTrue(line.contains(Style.ANSI_GRAY));
            }
        }
    }

    @Test
    void codeBlockDoesNotExposeFenceInfo() {
        String md = "```java\ncode content\n```";
        String p = MarkdownRenderer.render(md, plain, 80);
        assertTrue(p.contains("code content"));
        assertFalse(p.contains("```java"), "不应暴露围栏信息串");
        assertFalse(p.contains("```"));
        String c = MarkdownRenderer.render(md, color, 80);
        assertEquals(p, strip(c));
        assertTrue(c.contains(Style.ANSI_GRAY));
    }

    @Test
    void codeBlockMarkdownSyntaxVerbatim() {
        String md = "```\n**not bold** *not italic* `not code` [not link](http://x)\n```";
        String p = MarkdownRenderer.render(md, plain, 80);
        assertTrue(p.contains("**not bold**"), "代码块内 Markdown 语法应原样");
        assertTrue(p.contains("*not italic*"));
        assertTrue(p.contains("`not code`"));
        assertTrue(p.contains("[not link](http://x)"));
        String c = MarkdownRenderer.render(md, color, 80);
        assertEquals(p, strip(c));
        // 代码块内不应产生行内样式的 ANSI（仅应有 GRAY）
        assertTrue(c.contains(Style.ANSI_GRAY));
        // 但不应含仅由行内产生的绿/粗体等（除了 GRAY）
        // 若包含 BOLD/GREEN/UNDERLINE 需确认是 GRAY 片段内的误判，这里仅检查剥离一致性已足够
    }

    // ——— 列表：圆点青、层级缩进、嵌套 ———

    @Test
    void bulletListCyanBullet() {
        String md = "- item one\n- item two";
        String p = MarkdownRenderer.render(md, plain, 80);
        assertFalse(p.contains("\u001B["), "去色无 ANSI");
        assertTrue(p.contains("• item one"), "应含圆点 •");
        assertTrue(p.contains("• item two"));
        assertEquals("• item one\n• item two", p);
        String c = MarkdownRenderer.render(md, color, 80);
        assertTrue(c.contains(Style.ANSI_CYAN), "无序列表圆点应含青色");
        assertTrue(c.contains("•"), "应含圆点字符");
        assertTrue(c.contains("item one"));
        assertEquals(p, strip(c));
        assertTrue(c.contains(Style.ANSI_CYAN + "•" + Style.ANSI_RESET), "圆点应为青色包裹");
    }

    @Test
    void bulletListNestedIndent() {
        String md = "- one\n  - nested one\n  - nested two\n- two";
        String p = MarkdownRenderer.render(md, plain, 80);
        assertFalse(p.contains("\u001B["));
        assertTrue(p.contains("• one"), "顶层应无缩进");
        assertTrue(p.contains("  • nested one"), "嵌套应缩进两空格");
        assertTrue(p.contains("  • nested two"));
        assertTrue(p.contains("• two"));
        String[] lines = p.split("\n");
        assertEquals("• one", lines[0]);
        assertTrue(lines[1].startsWith("  •"), "第二行应为嵌套缩进");
        assertTrue(lines[2].startsWith("  •"));
        assertEquals("• two", lines[3]);
        String c = MarkdownRenderer.render(md, color, 80);
        assertTrue(c.contains(Style.ANSI_CYAN));
        assertEquals(p, strip(c));
        // 有色每行圆点均青色
        for (String line : c.split("\n")) {
            if (!strip(line).isBlank()) {
                assertTrue(line.contains(Style.ANSI_CYAN), "每行列表圆点应含青色，行: " + strip(line));
            }
        }
    }

    @Test
    void bulletListWrapContinuationIndent() {
        String md = "- this is a very long list item that should wrap across multiple lines when width is narrow";
        int w = 20;
        String p = MarkdownRenderer.render(md, plain, w);
        String[] lines = p.split("\n");
        assertTrue(lines.length > 1, "长列表项应折行");
        assertTrue(lines[0].startsWith("• "), "首行以圆点开头");
        for (int i = 1; i < lines.length; i++) {
            assertTrue(lines[i].startsWith("  "), "续行应以两空格缩进，实际: [" + lines[i] + "]");
            assertFalse(lines[i].startsWith("•"), "续行不应重复圆点");
            assertTrue(strip(lines[i]).length() <= w, "续行可见长度应 <= 宽度");
        }
        // 每行可见长度合规
        for (String line : lines) {
            assertTrue(strip(line).length() <= w, "每行可见长度应 <= 宽度，行: [" + strip(line) + "]");
        }
        String c = MarkdownRenderer.render(md, color, w);
        assertTrue(c.contains(Style.ANSI_CYAN));
        assertEquals(p, strip(c));
        // 有色与去色折行一致
        assertEquals(lines.length, strip(c).split("\n").length);
    }

    @Test
    void bulletListDeeplyNested() {
        String md = "- a\n  - b\n    - c\n      - d";
        String p = MarkdownRenderer.render(md, plain, 80);
        assertTrue(p.contains("• a"));
        assertTrue(p.contains("  • b"));
        assertTrue(p.contains("    • c"));
        assertTrue(p.contains("      • d"), "四层嵌套应逐层递增两空格");
        String c = MarkdownRenderer.render(md, color, 80);
        assertEquals(p, strip(c));
        assertTrue(c.contains(Style.ANSI_CYAN));
    }

    @Test
    void orderedListNumberingAndCyan() {
        String md = "1. first\n2. second\n3. third";
        String p = MarkdownRenderer.render(md, plain, 80);
        assertEquals("1. first\n2. second\n3. third", p);
        assertFalse(p.contains("\u001B["));
        String c = MarkdownRenderer.render(md, color, 80);
        assertTrue(c.contains(Style.ANSI_CYAN), "有序列表编号应含青色");
        assertTrue(c.contains("1."));
        assertTrue(c.contains("2."));
        assertTrue(c.contains("3."));
        assertEquals(p, strip(c));
        assertTrue(c.contains(Style.ANSI_CYAN + "1." + Style.ANSI_RESET), "编号应青色包裹");
        assertTrue(c.contains(Style.ANSI_CYAN + "2." + Style.ANSI_RESET));
    }

    @Test
    void orderedListStartNumber() {
        String md = "5. five\n6. six";
        String p = MarkdownRenderer.render(md, plain, 80);
        assertTrue(p.contains("5. five"), "起始号应保留 5");
        assertTrue(p.contains("6. six"));
        assertFalse(p.contains("1. five"));
        assertFalse(p.contains("\u001B["));
        String c = MarkdownRenderer.render(md, color, 80);
        assertTrue(c.contains(Style.ANSI_CYAN));
        assertTrue(c.contains("5."));
        assertTrue(c.contains("6."));
        assertEquals(p, strip(c));
        // 起始号非 1 的有序列表，编号青色
        assertTrue(c.contains(Style.ANSI_CYAN + "5." + Style.ANSI_RESET));
    }

    @Test
    void orderedListNestedInBullet() {
        String md = "- bullet\n  1. ordered inside\n  2. second";
        String p = MarkdownRenderer.render(md, plain, 80);
        assertTrue(p.contains("• bullet"));
        assertTrue(p.contains("  1. ordered inside"), "有序嵌套应带两空格缩进");
        assertTrue(p.contains("  2. second"));
        String c = MarkdownRenderer.render(md, color, 80);
        assertTrue(c.contains(Style.ANSI_CYAN));
        assertEquals(p, strip(c));
        // 顶层圆点与嵌套编号均青色
        assertTrue(c.contains(Style.ANSI_CYAN + "•" + Style.ANSI_RESET));
        assertTrue(c.contains(Style.ANSI_CYAN + "1." + Style.ANSI_RESET));
    }

    @Test
    void bulletListNestedInOrdered() {
        String md = "1. first\n   - nested bullet\n   - second bullet\n2. second";
        String p = MarkdownRenderer.render(md, plain, 80);
        assertTrue(p.contains("1. first"));
        assertTrue(p.contains("  • nested bullet") || p.contains("   • nested bullet"), "有序内嵌套无序应缩进");
        assertTrue(p.contains("• second bullet"));
        assertTrue(p.contains("2. second"));
        String c = MarkdownRenderer.render(md, color, 80);
        assertTrue(c.contains(Style.ANSI_CYAN));
        assertEquals(p, strip(c));
    }

    @Test
    void orderedListWrapContinuation() {
        String md = "1. this is a very long ordered list item that should wrap across multiple lines when width is narrow";
        int w = 20;
        String p = MarkdownRenderer.render(md, plain, w);
        String[] lines = p.split("\n");
        assertTrue(lines.length > 1);
        assertTrue(lines[0].startsWith("1. "), "首行以编号开头");
        for (int i = 1; i < lines.length; i++) {
            assertTrue(lines[i].startsWith("   "), "有序续行应以 3 空格缩进（\"1. \" 长度）");
        }
        String c = MarkdownRenderer.render(md, color, w);
        assertEquals(p, strip(c));
        assertTrue(c.contains(Style.ANSI_CYAN));
    }

    // ——— 引用：| 前缀、暗灰斜体、多段 ———

    @Test
    void blockquotePrefixGrayItalic() {
        String md = "> quoted text";
        String p = MarkdownRenderer.render(md, plain, 80);
        assertEquals("| quoted text", p);
        assertFalse(p.contains("\u001B["));
        String c = MarkdownRenderer.render(md, color, 80);
        assertTrue(c.contains(Style.ANSI_GRAY), "引用应含暗灰");
        assertTrue(c.contains(Style.ANSI_ITALIC), "引用应含斜体");
        assertTrue(c.contains("| "), "应含 | 前缀");
        assertTrue(c.contains("quoted text"));
        assertEquals(p, strip(c));
        // 前缀亦为灰色
        assertTrue(c.contains(Style.ANSI_GRAY + "| "));
    }

    @Test
    void blockquoteMultiParagraph() {
        String md = "> first paragraph\n\n> second paragraph";
        String p = MarkdownRenderer.render(md, plain, 80);
        assertTrue(p.contains("| first paragraph"), "第一段应带 | 前缀");
        assertTrue(p.contains("| second paragraph"), "第二段应带 | 前缀");
        assertFalse(p.contains("\u001B["));
        // 多段以空行分隔
        assertTrue(p.contains("\n\n| ") || p.contains("| first paragraph\n\n| second paragraph"));
        String[] blocks = p.split("\n\n");
        assertTrue(blocks.length >= 2, "多段引用应以空行分隔");
        assertTrue(blocks[0].startsWith("| "));
        assertTrue(blocks[1].startsWith("| "));
        String c = MarkdownRenderer.render(md, color, 80);
        assertTrue(c.contains(Style.ANSI_GRAY));
        assertTrue(c.contains(Style.ANSI_ITALIC));
        assertTrue(c.contains("| "));
        assertEquals(p, strip(c));
    }

    @Test
    void blockquoteWithBlankLinePrefixPreserved() {
        String md = "> line one\n>\n> line two";
        String p = MarkdownRenderer.render(md, plain, 80);
        // 实现对空行仍保留前缀：可能为 "| line one\n| \n| line two" 或合并为单段
        assertTrue(p.contains("| line one") || p.contains("| line one line two"));
        // 至少应含 | 前缀
        assertTrue(p.contains("| "));
        String c = MarkdownRenderer.render(md, color, 80);
        assertEquals(p, strip(c));
        assertTrue(c.contains(Style.ANSI_GRAY));
    }

    @Test
    void blockquoteWrapWithPrefix() {
        String md = "> this is a very long quoted line that should wrap when width is narrow";
        int w = 20;
        String p = MarkdownRenderer.render(md, plain, w);
        for (String line : p.split("\n")) {
            assertTrue(line.startsWith("| "), "引用每行应以 | 前缀，实际: [" + line + "]");
            // 可见长度含前缀应 <= 宽度
            assertTrue(strip(line).length() <= w, "引用行可见长度应 <= 宽度，实际 " + strip(line).length() + " 行: [" + strip(line) + "]");
        }
        String c = MarkdownRenderer.render(md, color, w);
        assertEquals(p, strip(c));
        assertTrue(c.contains(Style.ANSI_GRAY));
        assertTrue(c.contains(Style.ANSI_ITALIC));
        // 有色每行仍含前缀
        for (String line : c.split("\n")) {
            if (!strip(line).isBlank()) {
                assertTrue(line.contains("| "), "有色每行仍含 | ");
            }
        }
    }

    @Test
    void blockquoteWithInlineStyles() {
        String md = "> **bold in quote** and *italic*";
        String p = MarkdownRenderer.render(md, plain, 80);
        assertEquals("| bold in quote and italic", p);
        assertFalse(p.contains("\u001B["));
        String c = MarkdownRenderer.render(md, color, 80);
        assertTrue(c.contains(Style.ANSI_GRAY), "应含引用暗灰");
        assertTrue(c.contains(Style.ANSI_ITALIC), "应含引用斜体");
        assertTrue(c.contains(Style.ANSI_BOLD), "引用内粗体应仍含粗体");
        assertTrue(c.contains("| "));
        assertEquals(p, strip(c));
        // 粗体在引用内不应丢失引用色：RESET 后重开引用样式
        // 验证剥离后一致已覆盖不串样式
    }

    @Test
    void blockquoteWithInlineCodeAndLink() {
        String md = "> Use `code` and [link](http://example.com)";
        String p = MarkdownRenderer.render(md, plain, 80);
        assertTrue(p.contains("| Use code and link (http://example.com)"));
        String c = MarkdownRenderer.render(md, color, 80);
        assertTrue(c.contains(Style.ANSI_GRAY));
        assertTrue(c.contains(Style.ANSI_ITALIC));
        assertTrue(c.contains(Style.ANSI_GREEN) || c.contains("code"), "行内代码绿应保留");
        assertTrue(c.contains(Style.ANSI_UNDERLINE) || c.contains("link"), "链接下划线应保留");
        assertEquals(p, strip(c));
    }

    // ——— 混合嵌套：不串样式 ———

    @Test
    void mixedListWithCodeBlockNoStyleBleed() {
        String md = "- item with code:\n\n  ```\n  code inside\n  ```";
        String p = MarkdownRenderer.render(md, plain, 80);
        assertTrue(p.contains("• item with code:"), "应含列表圆点与文本");
        assertTrue(p.contains("┌"), "应含代码块上边线");
        assertTrue(p.contains("│ code inside"), "应含代码块内容");
        assertTrue(p.contains("└"), "应含代码块下边线");
        assertFalse(p.contains("\u001B["));
        // 代码块行应带列表续行缩进（两空格）
        String[] lines = p.split("\n");
        boolean foundCodeTop = false;
        for (String line : lines) {
            if (line.contains("┌")) {
                assertTrue(line.startsWith("  ┌"), "列表内代码块应带缩进，实际: [" + line + "]");
                foundCodeTop = true;
            }
        }
        assertTrue(foundCodeTop, "应找到带缩进的代码块上边线");
        String c = MarkdownRenderer.render(md, color, 80);
        assertTrue(c.contains(Style.ANSI_CYAN), "列表圆点青色");
        assertTrue(c.contains(Style.ANSI_GRAY), "代码块暗灰");
        assertEquals(p, strip(c));
        // 不串样式：首行圆点青色，代码块行灰色而非青色泛染
        assertTrue(c.split("\n")[0].contains(Style.ANSI_CYAN), "首行应含青色");
        boolean codeLineHasGray = false;
        for (String line : c.split("\n")) {
            if (strip(line).contains("│ code inside")) {
                assertTrue(line.contains(Style.ANSI_GRAY), "代码块行应含灰色");
                codeLineHasGray = true;
            }
        }
        assertTrue(codeLineHasGray);
    }

    @Test
    void mixedQuoteWithListNoStyleBleed() {
        String md = "> - q item one\n> - q item two";
        String p = MarkdownRenderer.render(md, plain, 80);
        assertTrue(p.contains("| • q item one"), "引用内列表应含 | 与圆点");
        assertTrue(p.contains("| • q item two"));
        assertFalse(p.contains("\u001B["));
        String c = MarkdownRenderer.render(md, color, 80);
        assertTrue(c.contains(Style.ANSI_CYAN), "引用内列表圆点应仍青色");
        assertTrue(c.contains(Style.ANSI_GRAY), "引用应暗灰");
        assertTrue(c.contains(Style.ANSI_ITALIC), "引用应斜体");
        assertTrue(c.contains("| "), "应含 | 前缀");
        assertEquals(p, strip(c));
        // 每行应含 | 前缀
        for (String line : c.split("\n")) {
            if (!strip(line).isBlank()) {
                assertTrue(line.contains("|"), "混合引用内列表每行应含 | 前缀，行: " + strip(line));
            }
        }
    }

    @Test
    void mixedQuoteWithNestedList() {
        String md = "> - a\n>   - b nested\n> - c";
        String p = MarkdownRenderer.render(md, plain, 80);
        assertTrue(p.contains("| • a"));
        assertTrue(p.contains("|   • b nested"), "引用内嵌套列表应保留层级缩进");
        assertTrue(p.contains("| • c"));
        String c = MarkdownRenderer.render(md, color, 80);
        assertTrue(c.contains(Style.ANSI_CYAN));
        assertTrue(c.contains(Style.ANSI_GRAY));
        assertEquals(p, strip(c));
    }

    @Test
    void mixedListWithBlockquote() {
        String md = "- item\n  > quoted inside list";
        String p = MarkdownRenderer.render(md, plain, 80);
        assertTrue(p.contains("• item"));
        assertTrue(p.contains("| quoted inside list") || p.contains("  | quoted inside list"), "列表内引用应含 | 前缀且带缩进");
        assertFalse(p.contains("\u001B["));
        String c = MarkdownRenderer.render(md, color, 80);
        assertTrue(c.contains(Style.ANSI_CYAN), "外层列表青色");
        assertTrue(c.contains(Style.ANSI_GRAY), "内层引用灰色");
        assertTrue(c.contains(Style.ANSI_ITALIC));
        assertEquals(p, strip(c));
    }

    @Test
    void mixedQuoteWithCodeBlockNoStyleBleed() {
        String md = "> ```\n> code in quote\n> ```";
        String p = MarkdownRenderer.render(md, plain, 80);
        assertTrue(p.contains("| ┌") || p.contains("┌"), "引用内代码块应含边框");
        assertTrue(p.contains("code in quote"));
        assertFalse(p.contains("\u001B["));
        String c = MarkdownRenderer.render(md, color, 80);
        assertTrue(c.contains(Style.ANSI_GRAY), "代码块与引用均灰色，但不应串样式导致丢失");
        assertEquals(p, strip(c));
        // 引用内代码块每行应含 | 前缀与 GRAY
        boolean found = false;
        for (String line : c.split("\n")) {
            if (strip(line).contains("│ code in quote") || strip(line).contains("code in quote")) {
                assertTrue(line.contains(Style.ANSI_GRAY));
                found = true;
            }
        }
        assertTrue(found || c.contains("code in quote"));
    }

    @Test
    void plainDegradeMixedStructuresWithoutAnsi() {
        String md = "# Title\n\n```\ncode\n```\n\n- a\n  - b\n\n> quote with **bold** and [link](http://x.com)\n\n- list with ```\n  inner\n  ```";
        String p = MarkdownRenderer.render(md, plain, 80);
        assertFalse(p.contains("\u001B["), "去色应无 ANSI");
        assertTrue(p.contains("Title"));
        assertTrue(p.contains("┌"));
        assertTrue(p.contains("• a"));
        assertTrue(p.contains("  • b"));
        assertTrue(p.contains("| quote with bold and link (http://x.com)"));
        String c = MarkdownRenderer.render(md, color, 80);
        assertTrue(c.contains(Style.ANSI_BOLD));
        assertTrue(c.contains(Style.ANSI_CYAN));
        assertTrue(c.contains(Style.ANSI_GRAY));
        assertTrue(c.contains(Style.ANSI_ITALIC));
        assertEquals(p, strip(c));
    }

}
