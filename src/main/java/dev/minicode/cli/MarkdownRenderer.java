package dev.minicode.cli;

import org.commonmark.Extension;
import org.commonmark.ext.gfm.strikethrough.Strikethrough;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.*;
import org.commonmark.parser.Parser;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 正文渲染器：纯函数，输入 Markdown 文本、样式开关、可用宽度，输出终端文本。
 * <p>
 * 约束：不读环境、不碰时钟；所有样式与宽度均由调用方注入，保持确定性与可测性。
 * 解析：commonmark 核心 + ext-gfm-tables/ext-gfm-strikethrough 官方扩展（ADR-0001，零传递依赖）；HTML 块按纯文本原样输出。
 * 样式映射：标题粗体青、粗体/斜体/删除线、行内代码绿、链接文本下划线+URL 暗灰；
 * 块级：代码块边框盒（上下边线+左竖线，内容暗灰，不折行）、列表青色圆点层级缩进、引用 | 前缀暗灰斜体；
 * 表格自适应对齐为 03 票范围，本文件仅保留透传不改实现；降级时同结构规则纯文本无 ANSI。
 * 折行：段落/标题/列表项/引用按注入宽度 ANSI 感知折行，ANSI 码不计宽，超长不可断词硬折行，代码块与表格行不折行。
 * </p>
 * 对应 spec：正文渲染 / 纯函数渲染缝；对齐 pi 的 marked + 自研主题架构。
 */
public final class MarkdownRenderer {

    // commonmark 解析器（单例复用，线程安全仅用于 parse，Parser 为不可变）
    // 扩展：GFM 表格 + GFM 删除线（同 artifact 家族、版本对齐 0.22.0，ADR-0001）
    private static final List<Extension> EXTENSIONS = Collections.unmodifiableList(
            Arrays.asList(TablesExtension.create(), StrikethroughExtension.create()));
    private static final Parser PARSER = Parser.builder().extensions(EXTENSIONS).build();

    private MarkdownRenderer() {
    }

    /**
     * 纯函数渲染入口。
     *
     * @param markdown Markdown 文本（可为 null/空）
     * @param style    样式开关（null 按去色处理）
     * @param width    可用宽度（注入，交互取终端宽度、管道 80；<=0 按 80 处理避免零宽崩）
     * @return 终端文本（去色时无 ANSI，有色时含 ANSI；空输入返回空串）
     */
    public static String render(String markdown, Style style, int width) {
        if (style == null) style = Style.PLAIN;
        if (markdown == null) return "";
        // 净化模型文本中的终端控制字节（裸 ESC/CSI/OSC），防它们以 ^[ 等形式漏到屏幕
        markdown = AnsiTextUtil.sanitizeTerminalControl(markdown);
        // 空白文本直接返回（避免解析产生空段落）
        if (markdown.isBlank()) return "";
        int w = AnsiTextUtil.normalizeWidth(width);

        Node document = PARSER.parse(markdown);
        if (document.getFirstChild() == null) {
            return "";
        }
        // 收集顶层块渲染结果
        java.util.ArrayList<String> blocks = new java.util.ArrayList<>();
        for (Node node = document.getFirstChild(); node != null; node = node.getNext()) {
            String rendered = renderBlock(node, style, w);
            if (rendered != null && !rendered.isEmpty()) {
                blocks.add(rendered);
            } else if (rendered != null && rendered.isEmpty()) {
                // 跳过空块
            }
        }
        if (blocks.isEmpty()) return "";
        return String.join("\n\n", blocks);
    }

    /**
     * 块级渲染分发（顶层，深度 0）。
     */
    private static String renderBlock(Node node, Style style, int width) {
        return renderBlock(node, style, width, 0);
    }

    /**
     * 块级渲染分发（带深度，供列表嵌套计算缩进）。
     * 深度仅影响列表的缩进层级，其他块忽略。
     */
    private static String renderBlock(Node node, Style style, int width, int depth) {
        if (node instanceof Heading heading) {
            String inner = renderInlines(heading, style);
            String styled;
            if (style.colorEnabled()) {
                // 标题外层为粗体青；若内层已产生 RESET（粗体/斜体等），需在 RESET 后重开标题样式以保持后续文本仍为标题色
                String headingOpen = Style.ANSI_BOLD + Style.ANSI_CYAN;
                if (inner.contains(Style.ANSI_RESET)) {
                    inner = inner.replace(Style.ANSI_RESET, Style.ANSI_RESET + headingOpen);
                }
                styled = headingOpen + inner + Style.ANSI_RESET;
            } else {
                styled = inner;
            }
            return wrapAnsi(styled, width);
        } else if (node instanceof Paragraph paragraph) {
            String inner = renderInlines(paragraph, style);
            if (inner.isEmpty()) return "";
            return wrapAnsi(inner, width);
        } else if (node instanceof ThematicBreak) {
            return "---";
        } else if (node instanceof FencedCodeBlock fenced) {
            String lit = fenced.getLiteral();
            return renderCodeBlock(lit, style);
        } else if (node instanceof IndentedCodeBlock indented) {
            String lit = indented.getLiteral();
            return renderCodeBlock(lit, style);
        } else if (node instanceof BulletList bullet) {
            return renderBulletList(bullet, style, width, depth);
        } else if (node instanceof OrderedList ordered) {
            return renderOrderedList(ordered, style, width, depth);
        } else if (node instanceof BlockQuote quote) {
            return renderBlockQuote(quote, style, width);
        } else if (node instanceof HtmlBlock html) {
            String lit = html.getLiteral();
            return lit != null ? stripTrailingNewline(lit) : "";
        } else if (isTableBlock(node)) {
            return renderTable(node, style, width);
        } else {
            // 未知块：尝试按行内渲染（可能为自定义块）或递归子块
            if (node.getFirstChild() != null) {
                // 先尝试行内
                String inlines = renderInlines(node, style);
                if (!inlines.isBlank()) {
                    return wrapAnsi(inlines, width);
                }
                // 再尝试块递归
                java.util.ArrayList<String> subs = new java.util.ArrayList<>();
                for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
                    String r = renderBlock(child, style, width, depth);
                    if (r != null && !r.isEmpty()) subs.add(r);
                }
                if (!subs.isEmpty()) return String.join("\n\n", subs);
            }
            return "";
        }
    }

    // ——— 代码块边框盒 ———

    /**
     * 围栏/缩进代码块渲染为边框盒：上下边线+左竖线，内容暗灰，保持原始行不折行，特殊字符原样。
     * 去色模式同结构纯文本（边框与前缀保留，无 ANSI）。
     * 边框长度按最长行动态计算，至少 3 个 ─，顶部 ┌+─、底部 └+─、每行前缀 │ 。
     */
    private static String renderCodeBlock(String literal, Style style) {
        if (literal == null) literal = "";
        String stripped = stripTrailingNewline(literal);
        String[] lines;
        if (stripped.isEmpty()) {
            lines = new String[0];
        } else {
            lines = stripped.split("\n", -1);
        }
        int maxVisible = 0;
        for (String l : lines) {
            int w = AnsiTextUtil.visibleLength(l);
            if (w > maxVisible) maxVisible = w;
        }
        // 全封闭盒：内宽 = maxVisible + 2（内容两侧各留 1 空格），四行同宽无缺口
        // 宽度按显示宽度计（CJK/emoji 计 2），否则 CJK 代码行会撑出边框
        int inner = Math.max(3, maxVisible) + 2;
        String top = "┌" + "─".repeat(inner) + "┐";
        String bottom = "└" + "─".repeat(inner) + "┘";
        if (style.colorEnabled()) {
            String grayTop = Style.ANSI_GRAY + top + Style.ANSI_RESET;
            String grayBottom = Style.ANSI_GRAY + bottom + Style.ANSI_RESET;
            StringBuilder sb = new StringBuilder();
            sb.append(grayTop);
            for (String line : lines) {
                sb.append("\n");
                String content = "│ " + AnsiTextUtil.padOrTruncateAnsi(line, maxVisible) + " │";
                sb.append(Style.ANSI_GRAY).append(content).append(Style.ANSI_RESET);
            }
            // 空代码块也需换行分隔上下边线
            sb.append("\n").append(grayBottom);
            return sb.toString();
        } else {
            StringBuilder sb = new StringBuilder();
            sb.append(top);
            for (String line : lines) {
                sb.append("\n");
                sb.append("│ ").append(AnsiTextUtil.padOrTruncateAnsi(line, maxVisible)).append(" │");
            }
            sb.append("\n").append(bottom);
            return sb.toString();
        }
    }

    // ——— 列表（青色圆点、层级缩进、嵌套） ———

    /** 无序列表渲染（深度控制缩进） */
    private static String renderBulletList(BulletList list, Style style, int width, int depth) {
        StringBuilder sb = new StringBuilder();
        for (Node child = list.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof ListItem item) {
                String itemStr = renderListItem(item, style, width, depth, false, 0);
                if (sb.length() > 0) sb.append("\n");
                sb.append(itemStr);
            }
        }
        return sb.toString();
    }

    /** 有序列表渲染（深度控制缩进，起始编号来自 OrderedList） */
    private static String renderOrderedList(OrderedList list, Style style, int width, int depth) {
        Integer markerStart = list.getMarkerStartNumber(); // getStartNumber() 已过时，用其替代
        int start = (markerStart != null) ? markerStart : 1;
        StringBuilder sb = new StringBuilder();
        int idx = 0;
        for (Node child = list.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof ListItem item) {
                int number = start + idx;
                String itemStr = renderListItem(item, style, width, depth, true, number);
                if (sb.length() > 0) sb.append("\n");
                sb.append(itemStr);
                idx++;
            }
        }
        return sb.toString();
    }

    /**
     * 列表项渲染：首行加 bullet（青色）、后续行及块级子结构按 continuation 缩进。
     * 列表项内可能包含段落、嵌套列表、代码块、引用等，需分别处理宽度与缩进，确保混合嵌套不串样式。
     */
    private static String renderListItem(ListItem item, Style style, int width, int depth, boolean ordered, int number) {
        String indent = "  ".repeat(depth);
        String bulletPlain;
        String bulletStyled;
        if (ordered) {
            bulletPlain = number + ". ";
            if (style.colorEnabled()) {
                bulletStyled = Style.ANSI_CYAN + number + "." + Style.ANSI_RESET + " ";
            } else {
                bulletStyled = bulletPlain;
            }
        } else {
            bulletPlain = "• ";
            if (style.colorEnabled()) {
                bulletStyled = Style.ANSI_CYAN + "•" + Style.ANSI_RESET + " ";
            } else {
                bulletStyled = bulletPlain;
            }
        }
        String firstPrefix = indent + bulletStyled;
        String firstPrefixPlain = indent + bulletPlain;
        int prefixVisibleLen = firstPrefixPlain.length();
        int effectiveWidth = width - prefixVisibleLen;
        if (effectiveWidth < 1) effectiveWidth = 1;
        String continuationPrefix = indent + " ".repeat(bulletPlain.length());

        ArrayList<String> outLines = new ArrayList<>();
        boolean isFirstBlock = true;
        for (Node child = item.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof BulletList bl) {
                String nested = renderBulletList(bl, style, width, depth + 1);
                if (outLines.isEmpty()) {
                    outLines.add(firstPrefix.trim().isEmpty() ? firstPrefix : firstPrefix.replaceAll("\\s+$", ""));
                }
                String[] nestedLines = nested.split("\n", -1);
                for (String nl : nestedLines) {
                    if (nl.isEmpty()) outLines.add("");
                    else outLines.add(nl);
                }
                isFirstBlock = false;
                continue;
            } else if (child instanceof OrderedList ol) {
                String nested = renderOrderedList(ol, style, width, depth + 1);
                if (outLines.isEmpty()) {
                    outLines.add(firstPrefix.replaceAll("\\s+$", ""));
                }
                String[] nestedLines = nested.split("\n", -1);
                for (String nl : nestedLines) {
                    if (nl.isEmpty()) outLines.add("");
                    else outLines.add(nl);
                }
                isFirstBlock = false;
                continue;
            } else {
                // 其他块：段落、代码块、引用等，需按有效宽度渲染后逐行加前缀
                String blockRendered = renderBlock(child, style, effectiveWidth, depth);
                if (blockRendered == null || blockRendered.isEmpty()) continue;
                String[] blockLines = blockRendered.split("\n", -1);
                for (int i = 0; i < blockLines.length; i++) {
                    String line = blockLines[i];
                    if (outLines.isEmpty() && isFirstBlock && i == 0) {
                        if (line.isEmpty()) outLines.add(firstPrefix);
                        else outLines.add(firstPrefix + line);
                    } else {
                        if (line.isEmpty()) outLines.add("");
                        else outLines.add(continuationPrefix + line);
                    }
                }
                isFirstBlock = false;
            }
        }
        if (outLines.isEmpty()) {
            return firstPrefix;
        }
        return String.join("\n", outLines);
    }

    // ——— 引用块（| 前缀、暗灰斜体、多段） ———

    /**
     * 引用块渲染：每行加 "| " 前缀，内容暗灰斜体；多段引用通过空行分隔的块拼接。
     * 内部宽度为外宽减前缀长度（2），确保折行不溢出；混合嵌套（引用内列表/代码块）通过递归保持不串样式。
     */
    private static String renderBlockQuote(BlockQuote quote, Style style, int width) {
        int prefixLen = 2; // "| ".length
        int innerWidth = width - prefixLen;
        if (innerWidth < 1) innerWidth = 1;
        ArrayList<String> innerBlocks = new ArrayList<>();
        for (Node child = quote.getFirstChild(); child != null; child = child.getNext()) {
            String r = renderBlock(child, style, innerWidth, 0);
            if (r != null && !r.isEmpty()) innerBlocks.add(r);
        }
        if (innerBlocks.isEmpty()) return "";
        String joined = String.join("\n\n", innerBlocks);
        String[] lines = joined.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        String prefixPlain = "| ";
        String prefixColored = style.colorEnabled() ? Style.ANSI_GRAY + "| " + Style.ANSI_RESET : prefixPlain;
        String quoteOpen = Style.ANSI_GRAY + Style.ANSI_ITALIC;
        String quoteClose = Style.ANSI_RESET;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (i > 0) sb.append("\n");
            if (line.isEmpty()) {
                if (style.colorEnabled()) {
                    sb.append(Style.ANSI_GRAY).append("| ").append(Style.ANSI_RESET);
                } else {
                    sb.append("| ");
                }
            } else {
                if (style.colorEnabled()) {
                    String inner = line;
                    if (inner.contains(Style.ANSI_RESET)) {
                        inner = inner.replace(Style.ANSI_RESET, Style.ANSI_RESET + quoteOpen);
                    }
                    String styled = quoteOpen + inner + quoteClose;
                    sb.append(prefixColored).append(styled);
                } else {
                    sb.append(prefixPlain).append(line);
                }
            }
        }
        return sb.toString();
    }

    /** 判断是否为表格块（通过类名避免编译期强依赖内部类） */
    private static boolean isTableBlock(Node node) {
        return node.getClass().getSimpleName().equals("TableBlock");
    }

    /**
     * 表格自适应渲染：列宽按内容自适应，可用宽度不足时按比例压缩仍保持对齐。
     * <p>
     * 样式：表头粗体、分隔线暗灰、数据行默认；PLAIN 下同规则纯文本。
     * 边界：单列、空单元格、极窄宽度不崩；表格行不折行，靠列宽压缩适配。
     * </p>
     */
    private static String renderTable(Node tableBlock, Style style, int width) {
        // 收集表头与表体行（样式前）
        java.util.ArrayList<java.util.ArrayList<String>> headerRows = new java.util.ArrayList<>();
        java.util.ArrayList<java.util.ArrayList<String>> bodyRows = new java.util.ArrayList<>();
        for (Node section = tableBlock.getFirstChild(); section != null; section = section.getNext()) {
            String sName = section.getClass().getSimpleName();
            boolean isHead = "TableHead".equals(sName);
            for (Node row = section.getFirstChild(); row != null; row = row.getNext()) {
                java.util.ArrayList<String> cells = new java.util.ArrayList<>();
                for (Node cell = row.getFirstChild(); cell != null; cell = cell.getNext()) {
                    String cellStyled = renderInlines(cell, style);
                    cells.add(cellStyled);
                }
                if (isHead) headerRows.add(cells);
                else bodyRows.add(cells);
            }
        }
        java.util.ArrayList<java.util.ArrayList<String>> allRows = new java.util.ArrayList<>();
        allRows.addAll(headerRows);
        allRows.addAll(bodyRows);
        if (allRows.isEmpty()) return "";
        int n = 0;
        for (java.util.ArrayList<String> r : allRows) n = Math.max(n, r.size());
        if (n == 0) return "";
        // 补齐列数
        for (java.util.ArrayList<String> r : allRows) while (r.size() < n) r.add("");
        for (java.util.ArrayList<String> r : headerRows) while (r.size() < n) r.add("");
        for (java.util.ArrayList<String> r : bodyRows) while (r.size() < n) r.add("");

        // 按可见长度计算期望列宽
        int[] desired = new int[n];
        for (java.util.ArrayList<String> r : allRows) {
            for (int i = 0; i < n; i++) {
                int vis = visibleLength(r.get(i));
                desired[i] = Math.max(desired[i], vis);
            }
        }
        for (int i = 0; i < n; i++) if (desired[i] == 0) desired[i] = 1;

        int w = AnsiTextUtil.normalizeWidth(width);
        int[] colWidths = computeColumnWidths(desired, w, n);

        // header 用 " | "，分隔线用 "-+-"，二者长度均为 3，保持 header/分隔线/数据行总可见长度一致
        String headerJoiner = " | ";
        String sepJ = "-+-";

        java.util.ArrayList<String> lines = new java.util.ArrayList<>();
        // 表头行（粗体）
        for (java.util.ArrayList<String> hRow : headerRows) {
            java.util.ArrayList<String> renderedCells = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                String padded = padOrTruncateAnsi(hRow.get(i), colWidths[i]);
                String wrapped = wrapHeaderCell(padded, style);
                renderedCells.add(wrapped);
            }
            lines.add(String.join(headerJoiner, renderedCells));
        }
        // 分隔线（暗灰）
        if (!headerRows.isEmpty()) {
            java.util.ArrayList<String> dashParts = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) dashParts.add("-".repeat(colWidths[i]));
            String plainSep = String.join(sepJ, dashParts);
            String styledSep = style.colorEnabled() ? Style.ANSI_GRAY + plainSep + Style.ANSI_RESET : plainSep;
            lines.add(styledSep);
        } else if (!bodyRows.isEmpty()) {
            // 无表头时仍需分隔线以保持结构？按 GFM 表总有表头，此分支防御
        }
        // 数据行（默认样式，保留行内样式如代码绿）
        for (java.util.ArrayList<String> bRow : bodyRows) {
            java.util.ArrayList<String> renderedCells = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                String padded = padOrTruncateAnsi(bRow.get(i), colWidths[i]);
                renderedCells.add(padded);
            }
            lines.add(String.join(headerJoiner, renderedCells));
        }
        return String.join("\n", lines);
    }

    /** 计算列宽：期望宽度内按比例压缩，最小 1，极窄时溢出但不崩。 */
    private static int[] computeColumnWidths(int[] desired, int width, int n) {
        int totalSep = (n - 1) * 3;
        int sumDesired = 0;
        for (int d : desired) sumDesired += d;
        int totalDesired = sumDesired + totalSep;
        if (totalDesired <= width) return desired.clone();
        int available = width - totalSep;
        if (available < n) {
            int[] min = new int[n];
            java.util.Arrays.fill(min, 1);
            return min;
        }
        if (sumDesired == 0) {
            int[] res = new int[n];
            int per = available / n;
            int rem = available % n;
            for (int i = 0; i < n; i++) res[i] = per + (i < rem ? 1 : 0);
            for (int i = 0; i < n; i++) if (res[i] < 1) res[i] = 1;
            return res;
        }
        int[] cw = new int[n];
        double[] fractions = new double[n];
        int sumFloor = 0;
        double ratio = (double) available / sumDesired;
        for (int i = 0; i < n; i++) {
            double exact = desired[i] * ratio;
            int fl = (int) Math.floor(exact);
            if (fl < 1) fl = 1;
            cw[i] = fl;
            fractions[i] = exact - Math.floor(exact);
            sumFloor += fl;
        }
        while (sumFloor > available) {
            int idx = -1;
            int maxVal = -1;
            for (int i = 0; i < n; i++) if (cw[i] > 1 && cw[i] > maxVal) { maxVal = cw[i]; idx = i; }
            if (idx == -1) break;
            cw[idx]--;
            sumFloor--;
        }
        while (sumFloor < available) {
            int idx = -1;
            double best = -1;
            for (int i = 0; i < n; i++) if (fractions[i] > best) { best = fractions[i]; idx = i; }
            if (idx == -1 || best < 0) {
                idx = -1;
                int maxDesired = -1;
                for (int i = 0; i < n; i++) if (cw[i] < desired[i] && desired[i] > maxDesired) { maxDesired = desired[i]; idx = i; }
                if (idx == -1) {
                    idx = 0;
                    for (int i = 1; i < n; i++) if (cw[i] < cw[idx]) idx = i;
                }
            }
            cw[idx]++;
            fractions[idx] = -1;
            sumFloor++;
        }
        return cw;
    }

    /** 将已带 ANSI 的单元格内容按可见宽度填补或截断至目标宽度（委托至 AnsiTextUtil 单一归口）。 */
    private static String padOrTruncateAnsi(String styled, int width) {
        return AnsiTextUtil.padOrTruncateAnsi(styled, width);
    }

    /** 按可见长度截断（委托至 AnsiTextUtil 单一归口）。 */
    private static String truncateVisible(String s, int width) {
        return AnsiTextUtil.truncateVisible(s, width);
    }

    /** 包装表头单元格为粗体，处理内层 RESET 后重开粗体以保持整格粗体。 */
    private static String wrapHeaderCell(String paddedStyled, Style style) {
        if (!style.colorEnabled() || paddedStyled == null || paddedStyled.isEmpty()) return paddedStyled;
        String withReopen = paddedStyled;
        if (withReopen.contains(Style.ANSI_RESET)) {
            withReopen = withReopen.replace(Style.ANSI_RESET, Style.ANSI_RESET + Style.ANSI_BOLD);
        }
        return Style.ANSI_BOLD + withReopen + Style.ANSI_RESET;
    }

    /**
     * 行内渲染：处理 Text / Emphasis / StrongEmphasis / Code / Link / Image / Soft/HardBreak / HtmlInline / Strikethrough。
     * 删除线由官方扩展 {@link Strikethrough} 节点承载，无手写正则。
     */
    private static String renderInlines(Node parent, Style style) {
        StringBuilder sb = new StringBuilder();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNext()) {
            if (node instanceof Text text) {
                sb.append(text.getLiteral());
            } else if (node instanceof Strikethrough strike) {
                String inner = renderInlines(strike, style);
                if (style.colorEnabled()) sb.append(Style.ANSI_STRIKETHROUGH).append(inner).append(Style.ANSI_RESET);
                else sb.append(inner);
            } else if (node instanceof Emphasis em) {
                String inner = renderInlines(em, style);
                if (style.colorEnabled()) sb.append(Style.ANSI_ITALIC).append(inner).append(Style.ANSI_RESET);
                else sb.append(inner);
            } else if (node instanceof StrongEmphasis strong) {
                String inner = renderInlines(strong, style);
                if (style.colorEnabled()) sb.append(Style.ANSI_BOLD).append(inner).append(Style.ANSI_RESET);
                else sb.append(inner);
            } else if (node instanceof Code code) {
                String lit = code.getLiteral();
                if (style.colorEnabled()) sb.append(Style.ANSI_GREEN).append(lit).append(Style.ANSI_RESET);
                else sb.append(lit);
            } else if (node instanceof Link link) {
                String textInner = renderInlines(link, style);
                String dest = link.getDestination();
                if (style.colorEnabled()) {
                    String underlined = Style.ANSI_UNDERLINE + textInner + Style.ANSI_RESET;
                    String dimUrl = Style.ANSI_GRAY + " (" + dest + ")" + Style.ANSI_RESET;
                    sb.append(underlined).append(dimUrl);
                } else {
                    sb.append(textInner).append(" (").append(dest).append(")");
                }
            } else if (node instanceof Image image) {
                String alt = renderInlines(image, style);
                String dest = image.getDestination();
                if (style.colorEnabled()) {
                    sb.append(alt).append(Style.ANSI_GRAY).append(" (").append(dest).append(")").append(Style.ANSI_RESET);
                } else {
                    sb.append(alt).append(" (").append(dest).append(")");
                }
            } else if (node instanceof SoftLineBreak) {
                sb.append(" ");
            } else if (node instanceof HardLineBreak) {
                sb.append("\n");
            } else if (node instanceof HtmlInline htmlInline) {
                sb.append(htmlInline.getLiteral());
            } else {
                // 兼容未直接导入的删除线节点（防御：若类路径无扩展但误含自定义节点，仍按删除线处理）
                String simpleName = node.getClass().getSimpleName();
                if ("Strikethrough".equals(simpleName)) {
                    String inner = renderInlines(node, style);
                    if (style.colorEnabled()) sb.append(Style.ANSI_STRIKETHROUGH).append(inner).append(Style.ANSI_RESET);
                    else sb.append(inner);
                } else {
                    // 未知行内：递归尝试
                    String inner = renderInlines(node, style);
                    sb.append(inner);
                }
            }
        }
        return sb.toString();
    }

    // ——— ANSI 感知折行（委托至 AnsiTextUtil 单一归口） ———

    static String wrapAnsi(String text, int width) {
        return AnsiTextUtil.wrapAnsi(text, width);
    }

    /** 在当前行缓冲中寻找活跃的样式 ANSI（委托至 AnsiTextUtil 单一归口）。 */
    private static String findActiveAnsi(String cur) {
        return AnsiTextUtil.findActiveAnsi(cur);
    }

    /** 可见长度（委托至 AnsiTextUtil 单一归口）。 */
    static int visibleLength(String s) {
        return AnsiTextUtil.visibleLength(s);
    }

    /** 剥离 ANSI 码（委托至 AnsiTextUtil 单一归口）。 */
    static String stripAnsi(String s) {
        return AnsiTextUtil.stripAnsi(s);
    }

    private static String stripTrailingNewline(String s) {
        if (s == null) return "";
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == '\n' || s.charAt(end - 1) == '\r')) end--;
        return s.substring(0, end);
    }
}
