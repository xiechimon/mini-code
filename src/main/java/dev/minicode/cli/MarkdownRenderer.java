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
            return renderTableFallback(node, style);
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

    /** 表格透传：按「文本 | 文本」与换行展开，保持可读，暂不做自适应列宽（后续票）。 */
    private static String renderTableFallback(Node tableBlock, Style style) {
        StringBuilder sb = new StringBuilder();
        for (Node section = tableBlock.getFirstChild(); section != null; section = section.getNext()) {
            for (Node row = section.getFirstChild(); row != null; row = row.getNext()) {
                StringBuilder rowSb = new StringBuilder();
                for (Node cell = row.getFirstChild(); cell != null; cell = cell.getNext()) {
                    String cellText = renderInlines(cell, style);
                    // 透传时剥离 ANSI 保持纯文本对齐预期（后续票会加表头粗体与列宽）
                    String plain = stripAnsi(cellText);
                    if (rowSb.length() > 0) rowSb.append(" | ");
                    rowSb.append(plain);
                }
                if (sb.length() > 0) sb.append("\n");
                sb.append(rowSb);
            }
        }
        return sb.toString();
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
