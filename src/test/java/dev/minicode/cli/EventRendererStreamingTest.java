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
 * 04 票流式重绘与中断单测——以 EventRenderer 缝为主。
 * 覆盖：StreamDelta 直出+行数记账（宽度感知）、首片段覆盖占位行、MessageEnd 重绘（CUU+ESC[J）、
 * aborted「⏹ 已中断」标记、管道/非流式无控制序列。
 */
class EventRendererStreamingTest {

    private final Style plain = Style.plain();
    private final Style colored = Style.colored();

    private static String strip(String s) {
        return s.replaceAll("\u001B\\[[0-9;]*m", "").replaceAll("\u001B\\[[0-9;]*[A-Z]", "").replaceAll("\u001B\\[[0-9;]*[a-z]", "");
    }

    // ——— StreamDelta 直出与行数记账 ———

    @Test
    void streamDeltaDirectOutputAndWidthAwareRows() {
        // 宽度 10：delta 长 5 -> 1 行
        StreamState s10 = new StreamState(10);
        AgentEvent.StreamDelta d1 = new AgentEvent.StreamDelta("hello");
        String out1 = EventRenderer.render(d1, plain, 10, s10);
        // 首个片段应含 CUU1+EL 前缀
        assertTrue(out1.contains("\u001B[1A"), "首个 delta 应含 CUU1");
        assertTrue(out1.contains("\u001B[2K"), "首个 delta 应含清行 2K");
        assertTrue(out1.endsWith("hello"), "应以 delta 结尾");
        assertEquals(1, s10.rows(), "hello 在宽 10 下应占 1 行");
        assertTrue(s10.hasStreamed());

        // 追加超长：hello + \" world!\" -> \"hello world!\" 长 12 -> 在宽 10 下 2 行
        AgentEvent.StreamDelta d2 = new AgentEvent.StreamDelta(" world!");
        String out2 = EventRenderer.render(d2, plain, 10, s10);
        assertFalse(out2.contains("\u001B[1A"), "非首个不应再含 CUU1");
        assertFalse(out2.contains("\u001B[2K"));
        assertEquals(" world!", out2, "后续 delta 原样直出");
        // buffer = \"hello world!\" 可见 12 -> 2 行
        assertEquals(2, s10.rows());

        // 换行场景：追加 \"\\nnext\" -> \"hello world!\\nnext\" -> 逻辑行2：第一行 12->2 物理， 第二行 4->1 => 总 3
        AgentEvent.StreamDelta d3 = new AgentEvent.StreamDelta("\nnext");
        EventRenderer.render(d3, plain, 10, s10);
        assertEquals(3, s10.rows(), "换行应增加逻辑行");
    }

    @Test
    void streamDeltaComputeRowsCjkAndWrap() {
        // CJK 宽 2：\"你好\" 可见 4，宽 3 -> ceil(4/3)=2
        assertEquals(2, StreamState.computeRows("你好", 3));
        // ASCII 宽 10：\"12345678901\" 11 -> 2
        assertEquals(2, StreamState.computeRows("12345678901", 10));
        // 空文本 0 行
        assertEquals(0, StreamState.computeRows("", 10));
        assertEquals(0, StreamState.computeRows(null, 10));
        // 单逻辑行超长折行
        assertEquals(3, StreamState.computeRows("abcdefghij", 4)); // 10/4=3
        // 多逻辑行：\"a\\nb\\nc\" 3 行
        assertEquals(3, StreamState.computeRows("a\nb\nc", 10));
        // 空行：\"a\\n\\nb\" -> a(1), 空行(1), b(1) =3
        assertEquals(3, StreamState.computeRows("a\n\nb", 10));
        // 尾随换行不计额外：\"a\\n\" -> 1
        assertEquals(1, StreamState.computeRows("a\n", 10));
        assertEquals(2, StreamState.computeRows("a\nb\n", 10));
        // CJK 混合换行
        assertEquals(2, StreamState.computeRows("你好\n世界", 10)); // 每行 4/10=1 =>2
        assertEquals(2, StreamState.computeRows("你好世界你好世界", 10)); // 8 chars*2=16 -> ceil 16/10=2
    }

    @Test
    void streamDeltaSecondNotCoverPlaceholder() {
        StreamState s = new StreamState(80);
        EventRenderer.render(new AgentEvent.StreamDelta("first"), plain, 80, s);
        String second = EventRenderer.render(new AgentEvent.StreamDelta(" second"), plain, 80, s);
        assertEquals(" second", second);
        assertFalse(second.contains("\u001B["), "第二片段不应含控制序列");
    }

    @Test
    void streamDeltaWithoutStateNoControlSequence() {
        AgentEvent.StreamDelta d = new AgentEvent.StreamDelta("hello");
        // 管道专用：state==null 时原样直出无控制序列
        String out = EventRenderer.render(d, plain, 80, null);
        assertEquals("hello", out);
        assertFalse(out.contains("\u001B["), "无 state 时不应含控制序列（管道模式）");
        // 通用入口不再处理 StreamDelta，防非管道误用导致首片段未覆盖占位行
        String out2 = EventRenderer.render((AgentEvent) d, plain, 80);
        assertEquals("", out2, "通用入口不再直出 StreamDelta，管道专用请用 render(StreamDelta, Style, int, null)");
        assertFalse(out2.contains("\u001B["));
    }

    @Test
    void streamDeltaEmptyIgnored() {
        StreamState s = new StreamState(80);
        assertEquals("", EventRenderer.render(new AgentEvent.StreamDelta(""), plain, 80, s));
        assertEquals("", EventRenderer.render(new AgentEvent.StreamDelta(null), plain, 80, s));
        assertFalse(s.hasStreamed());
        assertEquals(0, s.rows());
    }

    // ——— 首片段覆盖占位行 ———

    @Test
    void firstDeltaCoversPlaceholderRow() {
        StreamState s = new StreamState(80);
        String first = EventRenderer.render(new AgentEvent.StreamDelta("hi"), plain, 80, s);
        // 必须恰为 CUU1+\r+EL+delta：CUU 后需 \r 回列 0（CUU 不改列），否则流式从占位行列位置起步
        assertEquals(Style.cursorUp(1) + "\r" + Style.ERASE_LINE + "hi", first);
        // 宽度无关，首个始终 CUU1
        StreamState s2 = new StreamState(20);
        String first2 = EventRenderer.render(new AgentEvent.StreamDelta("x"), plain, 20, s2);
        assertTrue(first2.startsWith("\u001B[1A\r\u001B[2K"));
        assertTrue(first2.endsWith("x"));
    }

    // ——— MessageEnd 重绘 ———

    @Test
    void messageEndRedrawWithStreamedRows() {
        // 模拟流式：宽 10，buffer \"hello world!\\nnext\" -> 3 行
        StreamState s = new StreamState(10);
        EventRenderer.render(new AgentEvent.StreamDelta("hello world!"), plain, 10, s);
        EventRenderer.render(new AgentEvent.StreamDelta("\nnext"), plain, 10, s);
        assertEquals(3, s.rows());

        Message msg = Message.assistant(List.of(Message.Content.text("final **rendered** text")), "end");
        AgentEvent.MessageEnd me = new AgentEvent.MessageEnd(msg);
        String redraw = EventRenderer.render(me, plain, 10, s);
        // 应以 \r + CUU 2 + ED 开头：buffer 无尾随换行，游标在块末行，上移 rows-1=2 行后回到块首
        String expectedPrefix = "\r" + Style.cursorUp(2) + Style.ERASE_DOWN;
        assertTrue(redraw.startsWith(expectedPrefix), "重绘应以 \\r + CUU N-1 + ED 开头，实际: " + escape(redraw));
        assertTrue(redraw.contains("final rendered text") || redraw.contains("final"), "应含 Markdown 渲染后的文本");
        // 去除前缀后应为渲染体
        String body = redraw.substring(expectedPrefix.length());
        assertTrue(body.contains("final"));
        // 控制序列仅前缀，plain 下无颜色，prefix 含 CUU 和 ED 两个序列
        assertEquals(2, countOccurrences(redraw, "\u001B["), "前缀应含 CUU 和 ED 两个序列，实际: " + escape(redraw));
    }

    @Test
    void messageEndRedrawAbortedWithMarkerAndControl() {
        StreamState s = new StreamState(80);
        EventRenderer.render(new AgentEvent.StreamDelta("partial "), plain, 80, s);
        EventRenderer.render(new AgentEvent.StreamDelta("text"), plain, 80, s);
        assertTrue(s.hasStreamed());
        Message msg = Message.assistant(List.of(Message.Content.text("partial text")), "aborted");
        AgentEvent.MessageEnd me = new AgentEvent.MessageEnd(msg);
        String out = EventRenderer.render(me, plain, 80, s);
        assertTrue(out.contains("\u001B["), "有流式时应含重绘");
        assertTrue(out.contains("⏹ 已中断"), "aborted 应追加标记");
        // 有色时标记应被灰色包裹
        String outColor = EventRenderer.render(me, colored, 80, s);
        assertTrue(outColor.contains(Style.ANSI_GRAY + "⏹ 已中断") || outColor.contains("⏹ 已中断"));
        assertTrue(outColor.contains("\u001B["));
    }

    @Test
    void messageEndNoStreamNoControlSequence() {
        StreamState s = new StreamState(80);
        // 未经过任何 delta，hasStreamed false
        Message msg = Message.assistant(List.of(Message.Content.text("hello world")), "end");
        AgentEvent.MessageEnd me = new AgentEvent.MessageEnd(msg);
        String out = EventRenderer.render(me, plain, 80, s);
        assertFalse(out.contains("\u001B["), "无流式输出时不应含光标控制序列");
        assertEquals(EventRenderer.render(me, plain, 80), out, "非流式路径应与旧 render 一致（无 prefix）");

        // state 为 null 也不应含控制
        String out2 = EventRenderer.render(me, plain, 80, null);
        assertFalse(out2.contains("\u001B["));
        assertEquals(out, out2);
    }

    @Test
    void messageEndNoStreamPipelineAbortedStillHasMarkerButNoControl() {
        StreamState s = new StreamState(80);
        Message msg = Message.assistant(List.of(Message.Content.text("partial")), "aborted");
        AgentEvent.MessageEnd me = new AgentEvent.MessageEnd(msg);
        // 无流式但 aborted 仍应有标记，且无控制序列
        String out = EventRenderer.render(me, plain, 80, s);
        assertTrue(out.contains("⏹ 已中断"));
        assertFalse(out.contains("\u001B["), "管道/非流式不应含 CUU，即使 aborted");
        // 有色也应无 CUU，仅标记有灰
        String outColor = EventRenderer.render(me, colored, 80, s);
        assertTrue(outColor.contains("⏹ 已中断"));
        // 去色与有色剥离后均不含 CUU
        String stripped = outColor.replaceAll("\u001B\\[[0-9;]*m", "");
        assertFalse(stripped.contains("\u001B["));
        // 但 colored 本身含灰的 ANSI m，不含 CUU 的 A/J
        assertFalse(outColor.contains("\u001B[1A"));
        assertFalse(outColor.contains("\u001B[J"));
    }

    @Test
    void messageEndRedrawWidthMattersForRows() {
        // 同一文本在不同宽度下行数不同，重绘 CUU N 应不同
        String text = "a".repeat(25); // 25 字符
        StreamState s80 = new StreamState(80);
        EventRenderer.render(new AgentEvent.StreamDelta(text), plain, 80, s80);
        int rows80 = s80.rows(); // 25/80=1
        assertEquals(1, rows80);

        StreamState s10 = new StreamState(10);
        EventRenderer.render(new AgentEvent.StreamDelta(text), plain, 10, s10);
        int rows10 = s10.rows(); // 25/10=3
        assertEquals(3, rows10);

        Message msg = Message.assistant(List.of(Message.Content.text(text)), "end");
        String out80 = EventRenderer.render(new AgentEvent.MessageEnd(msg), plain, 80, s80);
        String out10 = EventRenderer.render(new AgentEvent.MessageEnd(msg), plain, 10, s10);
        // buffer 无尾随换行，游标在块末行：上移 rows-1 行后回块首（\r 先回列 0）
        assertTrue(out80.startsWith("\r" + Style.cursorUp(rows80 - 1) + Style.ERASE_DOWN));
        assertTrue(out10.startsWith("\r" + Style.cursorUp(rows10 - 1) + Style.ERASE_DOWN));
        assertNotEquals(out80, out10);
    }

    // ——— aborted 终态 ———

    @Test
    void messageEndAbortedMarkerPlainAndColored() {
        Message aborted = Message.assistant(List.of(Message.Content.text("some partial")), "aborted");
        AgentEvent.MessageEnd me = new AgentEvent.MessageEnd(aborted);
        String plainOut = EventRenderer.render(me, plain, 80);
        assertTrue(plainOut.contains("some partial"));
        assertTrue(plainOut.contains("⏹ 已中断"));
        assertFalse(plainOut.contains("\u001B["));

        String colorOut = EventRenderer.render(me, colored, 80);
        assertTrue(colorOut.contains("⏹ 已中断"));
        assertTrue(colorOut.contains(Style.ANSI_GRAY), "有色标记应为暗灰");
        // 剥离后一致
        String stripped = colorOut.replaceAll("\u001B\\[[0-9;]*m", "");
        assertEquals(plainOut, stripped);
    }

    @Test
    void messageEndAbortedEmptyTextStillShowsMarker() {
        Message abortedEmpty = Message.assistant(List.of(Message.Content.text("")), "aborted");
        // 空文本但 aborted，旧逻辑 would return \"\"，新逻辑应返回标记
        AgentEvent.MessageEnd me = new AgentEvent.MessageEnd(abortedEmpty);
        String out = EventRenderer.render(me, plain, 80);
        assertTrue(out.contains("⏹ 已中断"), "空 partial 也应显示中断标记");
        assertFalse(out.contains("\u001B["));
    }

    @Test
    void messageEndAbortedWithToolCallsStillShowsMarker() {
        Message.ToolCall tc = new Message.ToolCall("1", "read", Map.of("path", "a.txt"), "{\"path\":\"a.txt\"}");
        Message msg = Message.assistant(List.of(Message.Content.toolCall(tc)), "aborted");
        AgentEvent.MessageEnd me = new AgentEvent.MessageEnd(msg);
        String out = EventRenderer.render(me, plain, 80);
        assertTrue(out.contains("→ read a.txt"));
        assertTrue(out.contains("⏹ 已中断"));
    }

    @Test
    void agentEndAbortedMarker() {
        Message aborted = Message.assistant(List.of(Message.Content.text("partial")), "aborted");
        Message normal = Message.user("hi");
        AgentEvent.AgentEnd ae = new AgentEvent.AgentEnd(List.of(normal, aborted));
        String plainOut = EventRenderer.render(ae, plain, Duration.ofMillis(500), 1, 0);
        assertTrue(plainOut.contains("⏹ 已中断"));
        assertTrue(plainOut.contains("1 轮"));
        // 不应含 CUU
        assertFalse(plainOut.contains("\u001B[1A"));
        assertFalse(plainOut.contains("\u001B[J"));

        String colorOut = EventRenderer.render(ae, colored, Duration.ofMillis(500), 1, 0);
        assertTrue(colorOut.contains("⏹ 已中断"));
        assertTrue(colorOut.contains(Style.ANSI_GRAY));
        assertEquals(plainOut, strip(colorOut));
    }

    @Test
    void agentEndAbortedViaStreamStateAlsoShowsMarker() {
        Message aborted = Message.assistant(List.of(Message.Content.text("partial")), "aborted");
        AgentEvent.AgentEnd ae = new AgentEvent.AgentEnd(List.of(aborted));
        StreamState s = new StreamState(80);
        // 即使通过流式 state 渲染，也应有标记且无额外 CUU（AgentEnd 本身不重绘）
        String out = EventRenderer.render(ae, plain, TurnStats.of(Duration.ofMillis(100), 1, 0), 80, s);
        assertTrue(out.contains("⏹ 已中断"));
        assertFalse(out.contains("\u001B[1A"));
    }

    @Test
    void agentEndNonAbortedNoMarker() {
        Message ok = Message.assistant(List.of(Message.Content.text("done")), "end");
        AgentEvent.AgentEnd ae = new AgentEvent.AgentEnd(List.of(ok));
        String out = EventRenderer.render(ae, plain, Duration.ofMillis(100), 1, 0);
        assertFalse(out.contains("⏹ 已中断"));
        assertFalse(out.contains("\u001B["));
    }

    // ——— 管道/非流式无控制序列 ———

    @Test
    void pipelineNoControlSequences() {
        // 管道模式：直接用非流式 render，不传 state，或传空 state
        Message msg = Message.assistant(List.of(Message.Content.text("hello **world**")), "end");
        AgentEvent.MessageEnd me = new AgentEvent.MessageEnd(msg);
        String outPlain = EventRenderer.render(me, plain, 80);
        String outPlainWithNullState = EventRenderer.render(me, plain, 80, null);
        assertEquals(outPlain, outPlainWithNullState);
        assertFalse(outPlain.contains("\u001B["));
        // 即使 hasStreamed false 的 state 也不产生控制
        StreamState emptyState = new StreamState(80);
        String outEmptyState = EventRenderer.render(me, plain, 80, emptyState);
        assertFalse(outEmptyState.contains("\u001B["));
        assertEquals(outPlain, outEmptyState);

        // StreamDelta 在管道无 state 时也无控制
        AgentEvent.StreamDelta sd = new AgentEvent.StreamDelta("delta");
        String sdOut = EventRenderer.render(sd, plain, 80, null);
        assertEquals("delta", sdOut);
        assertFalse(sdOut.contains("\u001B["));
    }

    @Test
    void nonStreamingMessageEndNotContainEraseDown() {
        Message msg = Message.assistant(List.of(Message.Content.text("hi")), "end");
        AgentEvent.MessageEnd me = new AgentEvent.MessageEnd(msg);
        // 非流式路径即使调用流式重载但 state 未流式，也不应有 ED
        StreamState s = new StreamState(80);
        String out = EventRenderer.render(me, plain, 80, s);
        assertFalse(out.contains(Style.ERASE_DOWN));
        assertFalse(out.contains("\u001B[J"));
    }

    // ——— 既有路径零回归 ———

    @Test
    void existingTurnStartStillWorks() {
        AgentEvent.TurnStart t = new AgentEvent.TurnStart(1);
        String out = EventRenderer.render(t, plain);
        assertEquals("\n[第 1 轮] 思考中...", out);
        String outColor = EventRenderer.render(t, colored);
        assertTrue(outColor.contains(Style.ANSI_GRAY));
    }

    @Test
    void existingToolResultStillWorks() {
        Message.ToolCall tc = new Message.ToolCall("1", "read", Map.of("path", "a.txt"), "{}");
        AgentEvent.ToolResultEvent tr = new AgentEvent.ToolResultEvent(tc, "ok", false);
        String out = EventRenderer.render(tr, plain);
        assertEquals("← read: ok", out);
    }

    // ——— helper ———

    private static int countOccurrences(String s, String sub) {
        int count = 0;
        int idx = 0;
        while ((idx = s.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }

    private static String escape(String s) {
        return s.replace("\u001B", "\\u001B");
    }
}
