package dev.minicode.cli;

import java.io.PrintStream;

/**
 * 块级流式渲染器——等待反馈面的落地组件。
 * <p>
 * 线式终端的硬约束：滚出视口的内容（进入回滚缓冲区）不可擦除，因此「逐字上屏 + 事后重绘」
 * 对超长回复必然产生双份。本组件改为块级策略：模型增量累积进当前 markdown 块，
 * 屏幕上只保留一行可原位擦除的进度指示（▌ 已生成 N 字）；块完成（空行分界/围栏闭合）
 * 即擦除进度行并打印该块的渲染版——一次成型，永不重绘，滚出屏幕的都已是渲染终态。
 * </p>
 * <p>
 * 渲染仍走 {@link MarkdownRenderer} 纯函数缝；本组件只负责块边界检测与进度行的有状态输出。
 * </p>
 */
public final class BlockStreamer {

    private final Style style;
    private final int width;
    private final PrintStream out;
    private final StringBuilder block = new StringBuilder();
    private boolean progressVisible = false;
    private boolean printedAnyBlock = false;

    public BlockStreamer(Style style, int width, PrintStream out) {
        this.style = style == null ? Style.PLAIN : style;
        this.width = AnsiTextUtil.normalizeWidth(width);
        this.out = out;
    }

    /**
     * 追加流式增量：累积进当前块，并原位刷新单行进度指示。
     * 首次调用会用 \r+EL 覆盖 TurnStart 的「思考中」占位行。
     */
    public void delta(String d) {
        if (d == null || d.isEmpty()) return;
        block.append(d);
        // 块完成即打印渲染版；未完成才显示进度行
        if (!completeBlockIfNeeded()) showProgress();
    }

    /**
     * 若当前块已完成（空行分界或围栏闭合），擦除进度行并打印渲染块。
     *
     * @return 是否打印了渲染块
     */
    public boolean completeBlockIfNeeded() {
        String buf = block.toString();
        if (buf.isBlank()) return false;
        if (insideFence(buf)) return false;              // 围栏未闭合：继续累积
        boolean complete = endsWithClosingFence(buf) || endsWithBlankLine(buf);
        if (!complete) return false;
        printBlock(buf);
        return true;
    }

    /**
     * 终态冲刷：擦除进度行，打印最后的不完整块（如有）。
     *
     * @param aborted 生成是否被中断（中断时追加 ⏹ 标记）
     */
    public void flush(boolean aborted) {
        eraseProgress();
        String buf = block.toString();
        if (!buf.isBlank()) {
            out.println(renderBlock(buf));
            block.setLength(0);
        }
        if (aborted) {
            out.println(EventRenderer.maybeColor("⏹ 已中断", Style.ANSI_GRAY, style));
        }
    }

    /** 轮次重置：清空块缓冲（TurnStart 时调用）。 */
    public void reset() {
        block.setLength(0);
        progressVisible = false;
        printedAnyBlock = false;
    }

    private void showProgress() {
        if (!progressVisible) {
            out.print("\r" + Style.ERASE_LINE);          // 覆盖「思考中」占位行
            progressVisible = true;
        }
        out.print("\r" + Style.ERASE_LINE + EventRenderer.maybeColor(
                "▌ 已生成 " + charCount() + " 字", Style.ANSI_GRAY, style));
        out.flush();
    }

    private void printBlock(String text) {
        eraseProgress();
        if (printedAnyBlock) out.println();              // 块间空行分隔
        out.println(renderBlock(text));
        printedAnyBlock = true;
        block.setLength(0);
    }

    private void eraseProgress() {
        if (progressVisible) {
            out.print("\r" + Style.ERASE_LINE);
            progressVisible = false;
        }
    }

    private String renderBlock(String text) {
        return MarkdownRenderer.render(text, style, width);
    }

    private int charCount() {
        return block.codePointCount(0, block.length());
    }

    /** 缓冲是否处于未闭合的围栏内（代码块内允许空行，需等闭合围栏）。 */
    private static boolean insideFence(String buf) {
        boolean in = false;
        for (String line : buf.split("\n", -1)) {
            if (line.stripLeading().startsWith("```")) in = !in;
        }
        return in;
    }

    /** 缓冲是否以闭合围栏行结尾（容忍尾随换行）。 */
    private static boolean endsWithClosingFence(String buf) {
        String trimmed = buf.endsWith("\n") ? buf.substring(0, buf.length() - 1) : buf;
        String[] lines = trimmed.split("\n", -1);
        if (lines.length < 2) return false;
        if (!lines[lines.length - 1].stripLeading().startsWith("```")) return false;
        // 末行之前处于围栏内 → 末行是闭合围栏
        return insideFence(trimmed.substring(0, trimmed.lastIndexOf('\n')));
    }

    /** 缓冲是否以空行结尾（段落分界）。 */
    private static boolean endsWithBlankLine(String buf) {
        return buf.matches("(?s).*\\n\\s*\\n") || buf.endsWith("\n\n");
    }
}
