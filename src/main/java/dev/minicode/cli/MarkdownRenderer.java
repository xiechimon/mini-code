package dev.minicode.cli;

import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.*;
import org.commonmark.parser.Parser;

import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 正文渲染器：纯函数，输入 Markdown 文本、样式开关、可用宽度，输出终端文本。
 * <p>
 * 约束：不读环境、不碰时钟；所有样式与宽度均由调用方注入，保持确定性与可测性。
 * 解析：commonmark 核心 + ext-gfm-tables 官方扩展（ADR-0001，零传递依赖）；HTML 块按纯文本原样输出。
 * 样式映射（本票范围）：标题粗体青、粗体/斜体/删除线、行内代码绿、链接文本下划线+URL 暗灰；
 * 表格/代码块盒/列表/引用为后续票，本票按纯文本透传；降级时同结构规则纯文本无 ANSI。
 * 折行：段落/标题按注入宽度 ANSI 感知折行，ANSI 码不计宽，超长不可断词硬折行。
 * </p>
 * 对应 spec：正文渲染 / 纯函数渲染缝；对齐 pi 的 marked + 自研主题架构。
 */
public final class MarkdownRenderer {

    // ~~ 删除线 ~~ 的简易匹配（非扩展方案，commonmark 核心不解析 ~~）
    private static final Pattern STRIKETHROUGH_PATTERN = Pattern.compile("~~(.+?)~~");

    // commonmark 解析器（单例复用，线程安全仅用于 parse，Parser 为不可变）
    private static final List<Extension> EXTENSIONS = Collections.singletonList(TablesExtension.create());
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
        // 空白文本直接返回（避免解析产生空段落）
        if (markdown.isBlank()) return "";
        int w = width <= 0 ? 80 : width;

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
     * 块级渲染分发。
     * 本票已交付：段落、标题；其余（代码块/列表/引用/表格/分隔线/HTML）按纯文本透传以不阻塞管线贯通。
     */
    private static String renderBlock(Node node, Style style, int width) {
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
            if (lit == null) return "";
            // 透传：保持原始行不折行，去掉末尾多余空行
            return stripTrailingNewline(lit);
        } else if (node instanceof IndentedCodeBlock indented) {
            String lit = indented.getLiteral();
            if (lit == null) return "";
            return stripTrailingNewline(lit);
        } else if (node instanceof BulletList bullet) {
            // 本票透传：展开列表项为纯文本行（后续票补圆点与缩进）
            StringBuilder sb = new StringBuilder();
            for (Node child = bullet.getFirstChild(); child != null; child = child.getNext()) {
                if (child instanceof ListItem item) {
                    String itemText = renderListItemFallback(item, style, width);
                    if (!itemText.isEmpty()) {
                        if (sb.length() > 0) sb.append("\n");
                        sb.append(itemText);
                    }
                }
            }
            return sb.toString();
        } else if (node instanceof OrderedList ordered) {
            StringBuilder sb = new StringBuilder();
            for (Node child = ordered.getFirstChild(); child != null; child = child.getNext()) {
                if (child instanceof ListItem item) {
                    String itemText = renderListItemFallback(item, style, width);
                    if (!itemText.isEmpty()) {
                        if (sb.length() > 0) sb.append("\n");
                        sb.append(itemText);
                    }
                }
            }
            return sb.toString();
        } else if (node instanceof BlockQuote quote) {
            // 透传：递归渲染内部块并以换行拼接（后续票补 | 前缀与暗灰斜体）
            java.util.ArrayList<String> innerBlocks = new java.util.ArrayList<>();
            for (Node child = quote.getFirstChild(); child != null; child = child.getNext()) {
                String r = renderBlock(child, style, width);
                if (r != null && !r.isEmpty()) innerBlocks.add(r);
            }
            if (innerBlocks.isEmpty()) return "";
            return String.join("\n\n", innerBlocks);
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
                    String r = renderBlock(child, style, width);
                    if (r != null && !r.isEmpty()) subs.add(r);
                }
                if (!subs.isEmpty()) return String.join("\n\n", subs);
            }
            return "";
        }
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

        int w = width <= 0 ? 80 : width;
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
        // 若无 body 但有 header，已包含 header+分隔线；若表仅有 header 无 body，仍输出 header+分隔线
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

    /** 将已带 ANSI 的单元格内容按可见宽度填补或截断至目标宽度。 */
    private static String padOrTruncateAnsi(String styled, int width) {
        if (styled == null) styled = "";
        int vis = visibleLength(styled);
        if (vis == width) return styled;
        if (vis < width) return styled + " ".repeat(width - vis);
        return truncateVisible(styled, width);
    }

    /** 按可见长度截断，保留 ANSI 序列，截断后若处于样式内则补 RESET 避免串色。 */
    private static String truncateVisible(String s, int width) {
        if (s == null) return "";
        if (visibleLength(s) <= width) return s;
        StringBuilder out = new StringBuilder();
        int vis = 0;
        int i = 0;
        int n = s.length();
        while (i < n && vis < width) {
            char c = s.charAt(i);
            if (c == '\u001B' && i + 1 < n && s.charAt(i + 1) == '[') {
                int mIdx = s.indexOf('m', i);
                if (mIdx == -1) {
                    out.append(c);
                    vis++;
                    i++;
                } else {
                    out.append(s, i, mIdx + 1);
                    i = mIdx + 1;
                }
            } else {
                out.append(c);
                vis++;
                i++;
            }
        }
        String cur = out.toString();
        String active = findActiveAnsi(cur);
        if (active != null) cur = cur + Style.ANSI_RESET;
        return cur;
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

    /** 列表项透传：内部可能含段落等块，按块拼接。 */
    private static String renderListItemFallback(ListItem item, Style style, int width) {
        java.util.ArrayList<String> parts = new java.util.ArrayList<>();
        for (Node child = item.getFirstChild(); child != null; child = child.getNext()) {
            String r = renderBlock(child, style, width);
            if (r != null && !r.isEmpty()) parts.add(r);
        }
        if (parts.isEmpty()) {
            // 兜底：行内
            String inline = renderInlines(item, style);
            return inline;
        }
        return String.join("\n", parts);
    }

    /**
     * 行内渲染：处理 Text / Emphasis / StrongEmphasis / Code / Link / Image / Soft/HardBreak / HtmlInline。
     * 删除线通过 Text 中的 ~~ 语法在无扩展下手工处理；若未来引入 strikethrough 扩展则走节点分发。
     */
    private static String renderInlines(Node parent, Style style) {
        StringBuilder sb = new StringBuilder();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNext()) {
            if (node instanceof Text text) {
                sb.append(renderTextWithStrikethrough(text.getLiteral(), style));
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
                // 处理可能的删除线扩展节点（类名 Strikethrough）
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

    /**
     * 在无扩展情况下手工处理 ~~删除线~~。
     * 有色时包裹 ANSI_STRIKETHROUGH，PLAIN 时剥离标记保留文本。
     */
    private static String renderTextWithStrikethrough(String literal, Style style) {
        if (literal == null || literal.isEmpty()) return "";
        if (!literal.contains("~~")) return literal;
        Matcher m = STRIKETHROUGH_PATTERN.matcher(literal);
        StringBuffer buf = new StringBuffer();
        while (m.find()) {
            String inner = m.group(1);
            String repl;
            if (style.colorEnabled()) {
                repl = Matcher.quoteReplacement(Style.ANSI_STRIKETHROUGH + inner + Style.ANSI_RESET);
            } else {
                repl = Matcher.quoteReplacement(inner);
            }
            m.appendReplacement(buf, repl);
        }
        m.appendTail(buf);
        return buf.toString();
    }

    // ——— ANSI 感知折行 ———

    /**
     * ANSI 感知折行：按注入宽度折行，ANSI 码不计宽，超长不可断词硬折行。
     * <p>
     * 规则：优先在空格处断行；若单词本身超长则硬折；ANSI 序列不计宽且不被截断；
     * 折行后不保留行尾空格，硬折时若处于样式段内则补 RESET 并在下一行重开样式以保持视觉连续。
     * 纯函数实现，供段落/标题复用。
     * </p>
     */
    static String wrapAnsi(String text, int width) {
        if (text == null || text.isEmpty()) return text;
        if (width <= 0) return text;
        if (visibleLength(text) <= width) return text;

        StringBuilder result = new StringBuilder();
        StringBuilder cur = new StringBuilder();
        int visible = 0;
        int lastSpacePos = -1;
        int visibleAtLastSpace = -1;

        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            // ANSI 序列：ESC [ ... m
            if (c == '\u001B' && i + 1 < n && text.charAt(i + 1) == '[') {
                int mIdx = text.indexOf('m', i);
                if (mIdx == -1) {
                    // 异常：按普通字符处理
                    if (visible + 1 > width) {
                        if (lastSpacePos != -1) {
                            String before = cur.substring(0, lastSpacePos);
                            String after = cur.substring(lastSpacePos + 1);
                            String active = findActiveAnsi(before);
                            if (active != null && !before.endsWith(Style.ANSI_RESET)) {
                                result.append(before).append(Style.ANSI_RESET).append('\n');
                            } else {
                                result.append(before).append('\n');
                            }
                            cur.setLength(0);
                            if (active != null && !after.startsWith(active)) cur.append(active);
                            cur.append(after);
                            visible = visible - visibleAtLastSpace;
                            lastSpacePos = -1;
                            visibleAtLastSpace = -1;
                            continue;
                        } else {
                            String active = findActiveAnsi(cur.toString());
                            if (active != null) result.append(cur).append(Style.ANSI_RESET).append('\n').append(active);
                            else result.append(cur).append('\n');
                            cur.setLength(0);
                            if (active != null) cur.append(active);
                            visible = 0;
                            lastSpacePos = -1;
                            visibleAtLastSpace = -1;
                            continue;
                        }
                    }
                    cur.append(c);
                    visible++;
                    i++;
                } else {
                    String seq = text.substring(i, mIdx + 1);
                    cur.append(seq);
                    i = mIdx + 1;
                }
                continue;
            } else if (c == '\n') {
                result.append(cur).append('\n');
                cur.setLength(0);
                visible = 0;
                lastSpacePos = -1;
                visibleAtLastSpace = -1;
                i++;
                continue;
            } else if (c == ' ') {
                if (visible == 0) {
                    i++;
                    while (i < n && text.charAt(i) == ' ') i++;
                    continue;
                }
                if (visible + 1 > width) {
                    // 行已满，空格处断行（丢弃该空格）
                    String active = findActiveAnsi(cur.toString());
                    if (active != null && !cur.toString().endsWith(Style.ANSI_RESET)) {
                        result.append(cur).append(Style.ANSI_RESET).append('\n');
                    } else {
                        result.append(cur).append('\n');
                    }
                    cur.setLength(0);
                    if (active != null) cur.append(active);
                    visible = 0;
                    lastSpacePos = -1;
                    visibleAtLastSpace = -1;
                    i++;
                    while (i < n && text.charAt(i) == ' ') i++;
                    continue;
                }
                cur.append(c);
                visible++;
                lastSpacePos = cur.length() - 1;
                visibleAtLastSpace = visible;
                i++;
            } else {
                if (visible + 1 > width) {
                    if (lastSpacePos != -1) {
                        String before = cur.substring(0, lastSpacePos);
                        String after = cur.substring(lastSpacePos + 1);
                        String active = findActiveAnsi(before);
                        if (active != null && !before.endsWith(Style.ANSI_RESET)) {
                            result.append(before).append(Style.ANSI_RESET).append('\n');
                        } else {
                            result.append(before).append('\n');
                        }
                        cur.setLength(0);
                        if (active != null && !after.startsWith(active)) cur.append(active);
                        cur.append(after);
                        visible = visible - visibleAtLastSpace;
                        cur.append(c);
                        visible += 1;
                        lastSpacePos = -1;
                        visibleAtLastSpace = -1;
                        i++;
                    } else {
                        String active = findActiveAnsi(cur.toString());
                        if (active != null) result.append(cur).append(Style.ANSI_RESET).append('\n');
                        else result.append(cur).append('\n');
                        cur.setLength(0);
                        if (active != null) cur.append(active);
                        cur.append(c);
                        visible = 1;
                        lastSpacePos = -1;
                        visibleAtLastSpace = -1;
                        i++;
                    }
                } else {
                    cur.append(c);
                    visible++;
                    i++;
                }
            }
        }
        result.append(cur);
        return result.toString();
    }

    /**
     * 在当前行缓冲中寻找活跃的样式 ANSI（最近一次非 RESET 的样式码），用于硬折时在下一行重开。
     * 简化：寻找最后一个形如 ESC[3m/1m/32m/36m/4m/9m/90m 等且未被后续 RESET 关闭的序列。
     */
    private static String findActiveAnsi(String cur) {
        if (cur == null || cur.isEmpty()) return null;
        // 逆向查找 ANSI 序列
        // 收集所有序列
        java.util.ArrayList<String> seqs = new java.util.ArrayList<>();
        int idx = 0;
        while (idx < cur.length()) {
            int esc = cur.indexOf('\u001B', idx);
            if (esc == -1) break;
            int m = cur.indexOf('m', esc);
            if (m == -1) break;
            seqs.add(cur.substring(esc, m + 1));
            idx = m + 1;
        }
        if (seqs.isEmpty()) return null;
        // 从后往前找最近的非 RESET，且其后没有 RESET
        boolean hasResetAfter = false;
        for (int k = seqs.size() - 1; k >= 0; k--) {
            String s = seqs.get(k);
            if (s.equals(Style.ANSI_RESET)) {
                hasResetAfter = true;
                // RESET 后的样式已关闭，继续往前找之前的开启
                continue;
            }
            if (!hasResetAfter) {
                // 该样式未被重置，视为活跃
                // 仅对需要延续的样式重开：粗体/斜体/颜色/下划线/删除线
                // 直接返回该序列；若有多个连续样式（如 BOLD+CYAN），需一并返回
                // 简化：往前收集连续非 RESET 直到遇到 RESET 或开头
                StringBuilder active = new StringBuilder();
                for (int j = k; j >= 0; j--) {
                    String t = seqs.get(j);
                    if (t.equals(Style.ANSI_RESET)) break;
                    // 逆序插入保持原序
                    active.insert(0, t);
                }
                if (active.length() > 0) return active.toString();
                return s;
            } else {
                // 遇到 RESET 之后，说明更早的样式已被 RESET 关闭或被覆盖，是否还活跃取决于 RESET 前的样式？
                // 简化：如果已出现 RESET，认为无活跃样式（因为 RESET 清空）
                // 但若样式为嵌套，可能 RESET 后需要恢复父样式；本简化不处理嵌套恢复
                return null;
            }
        }
        return null;
    }

    /** 可见长度（剥离 ANSI 后） */
    static int visibleLength(String s) {
        if (s == null) return 0;
        return stripAnsi(s).length();
    }

    /** 剥离 ANSI 码 */
    static String stripAnsi(String s) {
        if (s == null) return "";
        return s.replaceAll("\u001B\\[[0-9;]*m", "");
    }

    private static String stripTrailingNewline(String s) {
        if (s == null) return "";
        // commonmark 的 FencedCodeBlock literal 末尾含换行，透传时去掉末尾换行以免双空行
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == '\n' || s.charAt(end - 1) == '\r')) end--;
        return s.substring(0, end);
    }
}
