package dev.minicode.cli;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BlockStreamer 单测：开放块原位重绘（正文可见增长）、块定稿无残留、围栏闭合才成盒、终态冲刷与中断标记。
 * 对应规格：行级流式渲染取代块级计数器——流式期间屏幕上应「看到正文」，而非仅一根「▌ 已生成 N 字」进度行。
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
    void firstDeltaErasesPlaceholderAndShowsText() {
        BlockStreamer bs = newStreamer(Style.PLAIN, 80);
        bs.delta("你好");
        String o = out();
        // 首个增量应先 \r+CUU(1)+EL 覆盖「思考中」占位行，再把正文直接上屏
        assertTrue(o.contains("\r[1A[2K"), "应覆盖占位行: " + o);
        assertTrue(o.contains("你好"), "正文应立即可见: " + o);
        assertFalse(o.contains("▌"), "不应退化为进度计数器: " + o);
    }

    @Test
    void streamGrowsAcrossDeltasWithoutCounter() {
        BlockStreamer bs = newStreamer(Style.PLAIN, 80);
        bs.delta("你好");
        captured.reset();
        bs.delta("世界");
        String o = out();
        // 增量直接累积到已上屏正文，而非仅更新计数
        assertTrue(o.contains("你好世界"), "正文应随增量增长: " + o);
        assertFalse(o.contains("▌"), "不应退化为进度计数器: " + o);
    }

    @Test
    void paragraphSealsOnBlankLineAndStripsMarkdownMarkers() {
        BlockStreamer bs = newStreamer(Style.PLAIN, 80);
        bs.delta("# 标题\n\n");
        String o = out();
        assertFalse(o.contains("▌"), "已完成块不应显示计数器: " + o);
        assertFalse(o.contains("#"), "定稿不应保留 # 标记: " + o);
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
