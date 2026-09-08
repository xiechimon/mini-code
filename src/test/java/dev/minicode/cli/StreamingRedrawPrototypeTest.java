package dev.minicode.cli;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 抛掷原型（throwaway）：验证 C 方案的「未完成块原位重绘」光标数学。
 * <p>
 * 机制：流式增量累积进当前开放块缓冲，每增量用 {@link MarkdownRenderer#render} 重渲染整块，
 * 并只重绘「本块占用的那几行」——打印渲染版前先 CUU 回本块首行 + ED 清到底 + 重打印，
 * 这样屏幕上始终是「本块当前渲染形态」，随增量生长但绝不产生双份、不暴露裸 markdown。
 * 块完成（空行分界/围栏闭合）即定稿打印，P 光标归位到定稿内容之后。
 * <p>
 * 本原型只验证光标数学（TermSim 忠实模拟 CUU/EL/ED/宽字符），不接生产代码。
 * 滚动视图外 cap 由生产实现另做（这也是 C 方案用「块级定稿」规避长围栏滚动问题的原因）。
 * </p>
 */
class StreamingRedrawPrototypeTest {

    /** 忠实终端模拟器（自包含拷贝）。 */
    static class TermSim {
        final int cols;
        final List<StringBuilder> rows = new ArrayList<>();
        int r = 0, c = 0;

        TermSim(int cols) { this.cols = cols; rows.add(new StringBuilder()); }

        void print(String s) {
            int i = 0;
            while (i < s.length()) {
                char ch = s.charAt(i);
                if (ch == 0x1B) {
                    if (i + 1 < s.length() && s.charAt(i + 1) == '[') {
                        int j = i + 2;
                        StringBuilder num = new StringBuilder();
                        while (j < s.length() && Character.isDigit(s.charAt(j))) num.append(s.charAt(j++));
                        char cmd = s.charAt(j);
                        int n = num.length() == 0 ? 1 : Integer.parseInt(num.toString());
                        switch (cmd) {
                            case 'A' -> r = Math.max(0, r - n);
                            case 'K' -> truncateRow(r, c);
                            case 'J' -> { truncateRow(r, c); while (rows.size() > r + 1) rows.remove(rows.size() - 1); }
                            default -> { }
                        }
                        i = j + 1;
                        continue;
                    }
                    i++;
                    continue;
                }
                if (ch == '\r') { c = 0; i++; continue; }
                if (ch == '\n') { r++; c = 0; ensure(r); i++; continue; }
                int cp = s.codePointAt(i);
                int w = cpWidth(cp);
                if (w > 0) {
                    if (c + w > cols) { r++; c = 0; }
                    ensure(r);
                    StringBuilder row = rows.get(r);
                    while (row.length() < c) row.append(' ');
                    row.append(new String(Character.toChars(cp)));
                    for (int pad = 1; pad < w; pad++) row.append(' ');
                    c += w;
                    if (c >= cols) { r++; c = 0; }
                }
                i += Character.charCount(cp);
            }
        }

        private void ensure(int idx) { while (rows.size() <= idx) rows.add(new StringBuilder()); }

        private void truncateRow(int idx, int col) {
            ensure(idx);
            StringBuilder row = rows.get(idx);
            if (row.length() > col) row.setLength(col);
            while (row.length() < col) row.append(' ');
        }

        String screen() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < rows.size(); i++) {
                if (i > 0) sb.append('\n');
                sb.append(rows.get(i));
            }
            return sb.toString();
        }
    }

    private static int cpWidth(int cp) {
        if (cp >= 0xFE00 && cp <= 0xFE0F) return 0;
        if ((cp >= 0x1100 && cp <= 0x115F) || (cp >= 0x2E80 && cp <= 0x9FFF)
                || (cp >= 0xAC00 && cp <= 0xD7A3) || (cp >= 0xF900 && cp <= 0xFAFF)
                || (cp >= 0xFF00 && cp <= 0xFF60) || (cp >= 0x1F300 && cp <= 0x1FAFF)) return 2;
        return 1;
    }

    /** 原型流式渲染器：累积开发块 → 每增量原位重绘 → 块完成定稿。 */
    static class PrototypeStreamer {
        private final Style style;
        private final int width;
        private final StringBuilder buffer = new StringBuilder();
        private int openRows = 0;      // 当前开发块在屏幕上占用的行数
        private boolean printedAny = false;

        PrototypeStreamer(Style style, int width) { this.style = style; this.width = width; }

        String delta(String d) {
            buffer.append(d);
            StringBuilder emit = new StringBuilder();
            // 块完成判断：空行分界或围栏闭合 → 定稿打印，不再重绘
            String buf = buffer.toString();
            if (isFinal(buf)) {
                emit.append(redrawToStart());
                String rendered = MarkdownRenderer.render(buf, style, width);
                emit.append(rendered).append('\n');
                buffer.setLength(0);
                openRows = 0;
                printedAny = true;
            } else {
                // 未完成：原位重绘开发块
                emit.append(redrawToStart());
                String rendered = MarkdownRenderer.render(buf, style, width);
                emit.append(rendered).append('\n');
                openRows = countRows(rendered);
            }
            return emit.toString();
        }

        /** 回列 0 + CUU 回本块首行 + ED 清到底（擦掉旧开发块）。 */
        private String redrawToStart() {
            StringBuilder sb = new StringBuilder();
            if (openRows > 0) {
                sb.append('\r').append(Style.cursorUp(openRows)).append(Style.ERASE_DOWN);
            }
            return sb.toString();
        }

        /** 定稿：清掉当前行（u 上已有 println）后归位。本原型简化——定稿前已 redrawToStart。 */
        static boolean isFinal(String buf) {
            return insideFence(buf) ? endsWithClosingFence(buf) : endsWithBlankLine(buf);
        }

        private static int countRows(String rendered) {
            if (rendered == null || rendered.isEmpty()) return 0;
            int n = 1;
            for (int i = 0; i < rendered.length(); i++) if (rendered.charAt(i) == '\n') n++;
            return n;
        }

        private static boolean insideFence(String buf) {
            boolean in = false;
            for (String line : buf.split("\n", -1)) {
                if (line.stripLeading().startsWith("```")) in = !in;
            }
            return in;
        }

        private static boolean endsWithClosingFence(String buf) {
            String trimmed = buf.endsWith("\n") ? buf.substring(0, buf.length() - 1) : buf;
            String[] lines = trimmed.split("\n", -1);
            if (lines.length < 2) return false;
            if (!lines[lines.length - 1].stripLeading().startsWith("```")) return false;
            return insideFence(trimmed.substring(0, trimmed.lastIndexOf('\n')));
        }

        private static boolean endsWithBlankLine(String buf) {
            return buf.matches("(?s).*\\n\\s*\\n") || buf.endsWith("\n\n");
        }
    }

    @Test
    void redrawGrowsTextInPlaceWithoutDuplicate() {
        TermSim sim = new TermSim(80);
        PrototypeStreamer ps = new PrototypeStreamer(Style.PLAIN, 80);
        // TurnStart 占位行（后续首片段会覆盖）
        sim.print("[第 1 轮] 思考中...\n");

        // 分段喂入一个未完成的段落 + 结尾空行触发定稿
        for (String frag : List.of("这是第一段", "普通文字，", "正在逐字流出，", "用户能看到增长。", "\n\n")) {
            sim.print(ps.delta(frag));
        }

        String screen = sim.screen();
        String flat = screen.replace("\r", "").replace(" ", "");
        // 关键断言：正文渲染恰一次，不重复、不残留裸 markdown
        long heading = flat.split("\n").length;
        assertFalse(flat.contains("这是第一段普通文字，正在逐字流出，用户能看到增长。\n这是第一段普通文字，正在逐字流出，用户能看到增长。"),
                "不应出现双份");
        assertFalse(flat.contains("**"), "不应暴露裸 markdown 标记");
        System.out.println("=== 终屏 ===\n" + screen);
    }

    @Test
    void midStreamTextIsVisibleNotJustCounter() {
        TermSim sim = new TermSim(80);
        PrototypeStreamer ps = new PrototypeStreamer(Style.PLAIN, 80);
        sim.print("[第 1 轮] 思考中...\n");
        sim.print(ps.delta("这是第一段普通文字"));
        // 未完成段落：屏幕上应已有正文（而非只出“已生成 N 字”计数器）
        String screen = sim.screen();
        String flat = screen.replace("\r", "").replace(" ", "");
        assertFalse(flat.contains("已生成"), "不应退化为计数器");
        assertFalse(flat.isEmpty(), "流式期间屏幕上应有正文");
        System.out.println("=== 流式中途 ===\n" + screen);
    }
}
