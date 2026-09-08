package dev.minicode.cli;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BlockStreamer 单测：进度行原位更新、块完成渲染、围栏语义、终态冲刷与中断标记。
 * 对应规格：块级流式渲染（滚出视口不可擦除 → 渲染块一次成型，进度行单行可擦除）。
 */
class BlockStreamerTest {

    private ByteArrayOutputStream captured;
    private BlockStreamer newStreamer(Style style, int width) {
        captured = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(captured, true, StandardCharsets.UTF_8);
        return new BlockStreamer(style, width, out);
    }

    private String out() {
        return captured.toString(StandardCharsets.UTF_8);
    }

    @Test
    void firstDeltaErasesPlaceholderAndShowsProgress() {
        BlockStreamer bs = newStreamer(Style.PLAIN, 80);
        bs.delta("你好");
        String o = out();
        // 首个增量应先 \r+EL 擦除 TurnStart 的「思考中」占位行，再显示进度
        assertTrue(o.contains("\r\u001B[2K"), "应擦除占位行: " + o);
        assertTrue(o.contains("▌ 已生成 2 字"), "进度应按码点计数: " + o);
    }

    @Test
    void progressCountsUpAcrossDeltas() {
        BlockStreamer bs = newStreamer(Style.PLAIN, 80);
        bs.delta("你好");
        captured.reset();
        bs.delta("世界");
        String o = out();
        assertTrue(o.contains("▌ 已生成 4 字"), "计数应跨增量累计: " + o);
    }

    @Test
    void paragraphCompletesOnBlankLineAndStripsMarkdownMarkers() {
        BlockStreamer bs = newStreamer(Style.PLAIN, 80);
        bs.delta("# 标题\n\n");
        String o = out();
        // 块在同一增量内完成：不显示进度行（无闪烁），直接渲染
        assertFalse(o.contains("▌"), "瞬时完成的块不应显示进度行: " + o);
        assertFalse(o.contains("#"), "渲染块不应保留 # 标记: " + o);
        assertTrue(o.contains("标题"));
    }

    @Test
    void consecutiveBlocksSeparatedByBlankLine() {
        BlockStreamer bs = newStreamer(Style.PLAIN, 80);
        bs.delta("第一段\n\n");
        captured.reset();
        bs.delta("第二段\n\n");
        String o = out();
        assertTrue(o.startsWith("\n"), "块间应有空行分隔: " + o);
        assertTrue(o.contains("第二段"));
    }

    @Test
    void blankLineInsideFenceDoesNotCompleteBlock() {
        BlockStreamer bs = newStreamer(Style.PLAIN, 80);
        bs.delta("```java\nint x = 1;\n\n");
        assertFalse(out().contains("┌"), "围栏内空行不应触发块完成");
        bs.delta("```\n");
        String o = out();
        assertTrue(o.contains("┌") && o.contains("int x = 1;"), "围栏闭合应渲染代码盒: " + o);
        assertFalse(o.contains("```java"), "不应暴露围栏标记");
    }

    @Test
    void flushRendersIncompleteBlockWithoutMarker() {
        BlockStreamer bs = newStreamer(Style.PLAIN, 80);
        bs.delta("半截段落");
        captured.reset();
        bs.flush(false);
        String o = out();
        assertTrue(o.contains("半截段落"), "终态应冲刷未完成块");
        assertFalse(o.contains("⏹"), "非中断不应有标记");
        assertFalse(o.contains("▌"), "进度行应被擦除");
    }

    @Test
    void flushAbortedAppendsInterruptMarker() {
        BlockStreamer bs = newStreamer(Style.PLAIN, 80);
        bs.delta("半截回复");
        captured.reset();
        bs.flush(true);
        String o = out();
        assertTrue(o.contains("半截回复"), "中断应保留已生成部分");
        assertTrue(o.contains("⏹ 已中断"), "中断应追加标记");
    }

    @Test
    void colorStyleAppliesToRenderedBlock() {
        BlockStreamer bs = newStreamer(Style.COLOR, 80);
        bs.delta("# 标题\n\n");
        String o = out();
        assertTrue(o.contains(Style.ANSI_BOLD) || o.contains(Style.ANSI_CYAN), "有色模式标题应带样式: " + o);
    }
}
