package dev.minicode.cli;

import java.io.PrintStream;

/**
 * 行级流式渲染器——等待反馈面的落地组件（取代「块级流式计数器」）。
 * <p>
 * 动机：块级策略把正文藏进块缓冲、只显示一根「▌ 已生成 N 字」进度行，块完成才整块倾销，
 * 用户看不到回复在流动（属治疗标不治本的绕法）。本组件改为「行级流式 + 块级定稿」：
 * 增量累积进当前开放块，已完成的块前缀（空行分界/围栏闭合）立即一次定稿打印、从此不可变；
 * 剩余未完成的开放块随增量子化重算本块渲染形态并**原位重绘**（CUU 回本块首行 + ED 清到底 + 重打印），
 * 文本随增长可见。所以滚出视口的内容已是渲染终态、绝不与重绘叠加双份。
 * </p>
 * <p>
 * 对齐 pi 源：pi-coding-agent 的流式渲染（滚动区内逐行追加而非整块重绘），本项目用「开放块原位重绘 +
 * 定稿不可变」在行式终端约束下逼近该效果。仍走 {@link MarkdownRenderer} 纯函数缝；本组件只持有有状态的
 * 部分（开放块缓冲、屏幕行数账、已定稿标记），不读环境、不碰时钟。首增量覆盖 TurnStart 的「思考中」占位行。
 * </p>
 */
public final class BlockStreamer {

    private final Style style;
    private final int width;
    private final PrintStream out;
    private final StringBuilder block = new StringBuilder();

    /** 视口高度（行）：开放块渲染行数超过它则放弃原位重绘、转入追加模式。<=0 视为无上限。 */
    private final int viewportRows;
    /** 是否已进入「追加模式」：开放块超出视口，CUU+ED 无法安全回滚，改为增量原样上屏。 */
    private boolean appendMode = false;

    /** 当前开放块在屏幕上占用的行数（0 = 尚未上屏）；含可选的块间空行分隔。 */
    private int openRows = 0;
    /** 是否已打印过至少一个定稿块（用于块间空行分隔与首触判断）。 */
    private boolean printedAnyBlock = false;
    /** 首个增量尚未到达，屏幕上仍是 TurnStart 的「思考中」占位行。 */
    private boolean placeholderPending = true;

    public BlockStreamer(Style style, int width, PrintStream out) {
        this(style, width, Integer.MAX_VALUE, out);
    }

    public BlockStreamer(Style style, int width, int viewportRows, PrintStream out) {
        this.style = style == null ? Style.PLAIN : style;
        this.width = AnsiTextUtil.normalizeWidth(width);
        this.out = out;
        this.viewportRows = viewportRows <= 0 ? Integer.MAX_VALUE : viewportRows;
    }

    /**
     * 追加流式增量：先定稿已完成的块前缀（一次打印），再将剩余未完成块原位重绘。
     * 首次调用会以 \r+CUU+EL 覆盖「思考中」占位行。
     */
    public void delta(String d) {
        if (d == null || d.isEmpty()) return;
        if (placeholderPending) {
            erasePlaceholder();
            placeholderPending = false;
        }
        block.append(d);
        sealCompletedPrefix();
        if (!blockHasContent()) {                       // 全部定稿清空
            openRows = 0;
            return;
        }
        if (appendMode) {                               // 已滚出视口：增量原样上屏，不再重绘
            out.print(d);
            out.flush();
            return;
        }
        redrawOpenBlock(d);                             // 剩余开放块：原位重绘，正文可见
    }

    /**
     * 终态冲刷：擦除当前开放块（或占位行），打印最后的不完整块（如有）。
     *
     * @param aborted 生成是否被中断（中断时追加 ⏹ 标记）
     */
    public void flush(boolean aborted) {
        if (placeholderPending) {
            erasePlaceholder();
            placeholderPending = false;
        } else {
            eraseOpenBlockRegion();
        }
        String buf = block.toString();
        if (!buf.isBlank() && !appendMode) {
            if (printedAnyBlock) out.println();
            out.println(renderBlock(buf));
            printedAnyBlock = true;
            block.setLength(0);
        } else if (!buf.isBlank()) {
            block.setLength(0);                          // 追加模式：已流式上屏，不再重绘
        }
        if (aborted) {
            out.println(EventRenderer.maybeColor("⏹ 已中断", Style.ANSI_GRAY, style));
        }
    }

    /** 消息开始重置：清空块缓冲（MessageStart 时调用，为一条新消息重新武装流式状态）。 */
    public void reset() {
        block.setLength(0);
        openRows = 0;
        printedAnyBlock = false;
        placeholderPending = true;
        appendMode = false;
    }

    /** @return 是否已进入追加模式（开放块超出视口，放弃重绘）。 */
    public boolean isAppendMode() {
        return appendMode;
    }

    /** @return 渲染用样式（构造时已归一）。 */
    public Style style() {
        return style;
    }

    /** @return 渲染用终端宽度（构造时已归一）。 */
    public int width() {
        return width;
    }

    /**
     * 定稿缓冲中最靠后的完整块前缀（仅当缓冲中存在未闭合围栏时才视围栏内部为未完成）。
     * 把该前缀一次打印为定稿，剩余部分（若有）作为新的开放块。返回是否定稿了任何内容。
     */
    private boolean sealCompletedPrefix() {
        String buf = block.toString();
        if (buf.isBlank()) return false;
        int sealLen = completedPrefixLength(buf);
        if (sealLen == 0) return false;
        String rest = buf.substring(sealLen);
        if (appendMode) {                                // 追加模式：块已流式上屏，定稿不重绘
            block.setLength(0);
            block.append(rest);
            if (printedAnyBlock) out.println();
            printedAnyBlock = true;
            return true;
        }
        String sealed = buf.substring(0, sealLen);
        String rendered = renderBlock(sealed);
        eraseOpenBlockRegion();
        if (printedAnyBlock) out.println();              // 块间空行分隔
        out.println(rendered);
        printedAnyBlock = true;
        block.setLength(0);
        block.append(rest);
        openRows = 0;                                    // 剩余部分作为新开放块，尚未上屏
        return true;
    }

    /** 原位重绘当前开放块：回本块首行 + 清到底 + 重打印渲染版。结构性块（围栏/缩进代码/表格）不重绘，闭合才成盒/成表。 */
    private void redrawOpenBlock(String d) {
        if (isStructuralBlock(block.toString())) {       // 结构性块开放期不逐字重绘
            if (openRows > 0) {                          // 此前曾被当作可流式块重绘过部分内容 → 先清除避免残留
                out.print("\r" + Style.cursorUp(openRows) + Style.ERASE_DOWN);
            }
            openRows = 0;
            return;
        }
        String rendered = renderBlock(block.toString());
        int rows = renderRows(rendered);
        if (rows == 0) {                                 // 无可渲染内容：不占屏幕
            openRows = 0;
            return;
        }
        // 非首块前留一行空行分隔；该分隔与渲染行同属可重绘区域，故计入 openRows
        int totalRows = rows + (printedAnyBlock ? 1 : 0);
        if (totalRows > viewportRows) {                  // 超出视口：放弃原位重绘（追不回滚出内容）
            appendMode = true;                           // 转入追加模式
            openRows = 0;
            out.print(d);                                // 本增量原样上屏，不丢内容、不重复
            out.flush();
            return;
        }
        eraseOpenBlockRegion();
        if (printedAnyBlock) out.println();              // 分隔空行（定稿内容与开放块之间）
        out.print(rendered);
        out.print("\n");                                 // 游标归位到块后一行的行首
        openRows = totalRows;
    }

    /** 擦除当前开放块相位：回列 0 + CUU 回开放块首行 + 清到底。 */
    private void eraseOpenBlockRegion() {
        if (openRows > 0) {
            out.print("\r" + Style.cursorUp(openRows) + Style.ERASE_DOWN);
        }
    }

    /** 擦除「思考中」占位行（游标在占位行下一行处，回列 0 + CUU(1) + 清行）。 */
    private void erasePlaceholder() {
        out.print("\r" + Style.cursorUp(1) + Style.ERASE_LINE);
    }

    private boolean blockHasContent() {
        return !block.toString().isBlank();
    }

    private String renderBlock(String text) {
        return MarkdownRenderer.render(text, style, width);
    }

    /** 渲染文本占用的终端行数（按 \n 计）。 */
    private static int renderRows(String rendered) {
        if (rendered == null || rendered.isEmpty()) return 0;
        int n = 1;
        for (int i = 0; i < rendered.length(); i++) {
            if (rendered.charAt(i) == '\n') n++;
        }
        return n;
    }

    /**
     * 计算缓冲中最后一个完整块的结束偏移（不含后续未完成内容）。
     * 规则（与 markdown 结构一致）：
     * - 不在围栏内的空行：结束其前一个块（分段/标题/列表等）
     * - 闭合围栏行：结束代码块
     * 返回 0 表示缓冲内没有完整块（全部视为待完成）。
     */
    private static int completedPrefixLength(String buf) {
        boolean inFence = false;
        int seal = 0;
        int offset = 0;
        String[] lines = buf.split("\n", -1);
        for (String line : lines) {
            int lineEnd = offset + line.length();
            int afterLine = lineEnd + 1;                 // 本行 '\n' 之后的位置
            boolean isFence = line.stripLeading().startsWith("```");
            if (line.isEmpty()) {
                if (!inFence) seal = afterLine;          // 空行分界结束前一完整块
            } else if (isFence) {
                inFence = !inFence;
                if (!inFence) seal = afterLine;          // 闭合围栏 → 代码块完成
            }
            offset = afterLine;
        }
        return Math.min(seal, buf.length());
    }

    /**
     * 当前缓冲是否为「结构性块」开放期——整形容器（代码围栏/缩进代码/GFM 表格），
     * 其渲染只在完整闭合时有意义，故开放期不逐字重绘，待闭合/空行定稿成盒成表。
     */
    private static boolean isStructuralBlock(String buf) {
        String body = buf.stripLeading();
        if (body.isEmpty()) return false;
        String firstLine = (body.indexOf('\n') >= 0 ? body.substring(0, body.indexOf('\n')) : body).trim();
        if (firstLine.startsWith("```")) return true;                     // 围栏代码
        if (firstLine.startsWith("    ") || firstLine.startsWith("\t")) return true; // 缩进代码
        return isTableShape(body);                                         // GFM 表格
    }

    /** 是否是 GFM 表格形状（按缓冲**起始结构**判定，保持确定性）：首个非空行以 | 起且含 ≥2 个 |。 */
    private static boolean isTableShape(String body) {
        for (String line : body.split("\n", -1)) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            // 表头以 | 起且含 ≥2 个 |（至少一个单元格分界）→ 结构性表格；只看首行，避免事后重分类擦掉已上屏内容
            return t.startsWith("|") && countChar(t, '|') >= 2;
        }
        return false;
    }

    private static int countChar(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++;
        return n;
    }
}
