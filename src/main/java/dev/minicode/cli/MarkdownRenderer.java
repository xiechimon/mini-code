package dev.minicode.cli;

import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.*;
import org.commonmark.parser.Parser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 正文渲染器：纯函数，输入 Markdown 文本、样式开关、可用宽度，输出终端文本。
 * <p>
 * 约束：不读环境、不碰时钟；所有样式与宽度均由调用方注入，保持确定性与可测性。
 * 解析：commonmark 核心 + ext-gfm-tables 官方扩展（ADR-0001，零传递依赖）；HTML 块按纯文本原样输出。
 * 样式映射：标题粗体青、粗体/斜体/删除线、行内代码绿、链接文本下划线+URL 暗灰；
 * 块级：代码块边框盒（上下边线+左竖线，内容暗灰，不折行）、列表青色圆点层级缩进、引用 | 前缀暗灰斜体；
 * 表格自适应对齐为 03 票范围，本文件仅保留透传不改实现；降级时同结构规则纯文本无 ANSI。
 * 折行：段落/标题/列表项/引用按注入宽度 ANSI 感知折行，ANSI 码不计宽，超长不可断词硬折行，代码块与表格行不折行。
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
        int maxLen = 0;
        for (String l : lines) {
            if (l.length() > maxLen) maxLen = l.length();
        }
        int borderLen = Math.max(3, maxLen);
        // 上下边线长度为 maxLen+2，使视觉上与内容区（"│ "+内容）对齐
        String top = "┌" + "─".repeat(borderLen + 2);
        String bottom = "└" + "─".repeat(borderLen + 2);
        if (style.colorEnabled()) {
            String grayTop = Style.ANSI_GRAY + top + Style.ANSI_RESET;
            String grayBottom = Style.ANSI_GRAY + bottom + Style.ANSI_RESET;
            StringBuilder sb = new StringBuilder();
            sb.append(grayTop);
            for (String line : lines) {
                sb.append("\n");
                String content = "│ " + line;
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
                sb.append("│ ").append(line);
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
        int start = list.getStartNumber();
        if (start == 0) {
            Integer ms = list.getMarkerStartNumber();
            if (ms != null) start = ms;
            else start = 1;
        }
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
                    // 外层 bullet 无文本内容的兜底：先输出 bullet 空行，再追加嵌套
                    // 若嵌套是首块且外层无其他文本，需保留外层 bullet 行
                    // 但常见 markdown 外层含段落，此分支不触发
                    outLines.add(firstPrefix.trim().isEmpty() ? firstPrefix : firstPrefix.replaceAll("\\s+$", ""));
                    // 若首行仅为 bullet，需去除其后的空格保持整洁，但保留 bullet 本身
                    // 避免双重空行，随后直接追加嵌套行
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
        // 清理首行若为孤立 bullet 空行且后接嵌套时的多余空格：保持首行仅 bullet
        // 对首行为 bullet 且内容为空的情况，已在上面处理
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
                // 空行仍保留前缀，保持引用块视觉连续性
                if (style.colorEnabled()) {
                    sb.append(Style.ANSI_GRAY).append("| ").append(Style.ANSI_RESET);
                } else {
                    sb.append("| ");
                }
            } else {
                if (style.colorEnabled()) {
                    String inner = line;
                    //  inner 可能已含 ANSI（如粗体、行内代码等），在每个 RESET 后重开引用样式，保持后续文本仍为引用色
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

    /** 列表项透传（旧）：保留供未知场景兜底，当前列表已由专用渲染接管 */
    @SuppressWarnings("unused")
    private static String renderListItemFallback(ListItem item, Style style, int width) {
        java.util.ArrayList<String> parts = new java.util.ArrayList<>();
        for (Node child = item.getFirstChild(); child != null; child = child.getNext()) {
            String r = renderBlock(child, style, width);
            if (r != null && !r.isEmpty()) parts.add(r);
        }
        if (parts.isEmpty()) {
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
        boolean hasResetAfter = false;
        for (int k = seqs.size() - 1; k >= 0; k--) {
            String s = seqs.get(k);
            if (s.equals(Style.ANSI_RESET)) {
                hasResetAfter = true;
                continue;
            }
            if (!hasResetAfter) {
                StringBuilder active = new StringBuilder();
                for (int j = k; j >= 0; j--) {
                    String t = seqs.get(j);
                    if (t.equals(Style.ANSI_RESET)) break;
                    active.insert(0, t);
                }
                if (active.length() > 0) return active.toString();
                return s;
            } else {
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
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == '\n' || s.charAt(end - 1) == '\r')) end--;
        return s.substring(0, end);
    }
}
