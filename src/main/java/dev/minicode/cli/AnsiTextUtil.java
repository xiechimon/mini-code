package dev.minicode.cli;

/**
 * ANSI 感知文本工具：剥离/可见长度/截断/填充/折行的单一归口。
 * <p>
 * 抽取前分布在 {@link MarkdownRenderer#truncateVisible}、{@code wrapAnsi}、{@code padOrTruncateAnsi}
 * 三处的同形 ANSI 循环（ESC[ ... m 不计宽、截断后补 RESET、硬折时 findActive 重开），收敛至此。
 * 同时承载宽度归一：{@link #DEFAULT_WIDTH}=80 与 {@link #normalizeWidth(int)} 供渲染器与调用方统一引用，
 * 避免多处 {@code width<=0?80} 魔数与分支漂移。
 * </p>
 * 纯函数工具类，不读环境、不碰时钟。
 */
public final class AnsiTextUtil {

    /** 默认宽度（管道/非法宽度回落），对齐 spec 固定 80 列。 */
    public static final int DEFAULT_WIDTH = 80;

    private AnsiTextUtil() {
    }

    /**
     * 宽度归一：<=0 回落默认宽度。
     */
    public static int normalizeWidth(int width) {
        return width <= 0 ? DEFAULT_WIDTH : width;
    }

    /** 剥离 ANSI 码（零依赖手写正则）。 */
    public static String stripAnsi(String s) {
        if (s == null) return "";
        return s.replaceAll("\u001B\\[[0-9;]*m", "");
    }

    /** 可见显示宽度（剥离 ANSI 后）：CJK/全角/常见 emoji 计 2 列，零宽字符计 0，其余 1 列。 */
    public static int visibleLength(String s) {
        if (s == null) return 0;
        return displayWidth(stripAnsi(s));
    }

    /**
     * 清除文本中的终端控制字节：CSI/OSC 序列与残余裸 ESC。
     * 模型正文不应携带终端控制字节——它们会以 ^[ 等形式漏到屏幕上。
     */
    public static String sanitizeTerminalControl(String s) {
        if (s == null || s.isEmpty()) return s;
        String t = s.replaceAll("\u001B\\[[0-9;:?<]*[A-Za-z]", "");            // CSI … 终止字母
        t = t.replaceAll("\u001B\\][^\u0007\u001B]*(\u0007|\u001B\\\\)", ""); // OSC … BEL/ST
        return t.replace("\u001B", "");                                          // 残余裸 ESC
    }

    /** 逐码点累加显示宽度。 */
    private static int displayWidth(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            w += charWidth(cp);
            i += Character.charCount(cp);
        }
        return w;
    }

    /**
     * 单码点显示宽度：宽字符（CJK/全角/Hangul/常见 emoji）计 2，其余计 1。
     * 务实范围表而非完整 EAW——覆盖终端场景绝大多数字符。
     */
    static int charWidth(int cp) {
        if (isZeroWidthCodePoint(cp)) return 0;
        return isWideCodePoint(cp) ? 2 : 1;
    }

    private static boolean isZeroWidthCodePoint(int cp) {
        return (cp >= 0x0300 && cp <= 0x036F)      // 组合附加符
                || (cp >= 0x200B && cp <= 0x200F)  // 零宽字符
                || (cp >= 0xFE00 && cp <= 0xFE0F)  // 变体选择符（emoji 采光）
                || (cp >= 0x20D0 && cp <= 0x20F0); // 组合符号
    }

    private static boolean isWideCodePoint(int cp) {
        return (cp >= 0x1100 && cp <= 0x115F)
                || (cp >= 0x2E80 && cp <= 0x303E)
                || (cp >= 0x3041 && cp <= 0x33FF)
                || (cp >= 0x3400 && cp <= 0x4DBF)
                || (cp >= 0x4E00 && cp <= 0x9FFF)
                || (cp >= 0xA000 && cp <= 0xA4CF)
                || (cp >= 0xAC00 && cp <= 0xD7A3)
                || (cp >= 0xF900 && cp <= 0xFAFF)
                || (cp >= 0xFE30 && cp <= 0xFE6F)
                || (cp >= 0xFF00 && cp <= 0xFF60)
                || (cp >= 0xFFE0 && cp <= 0xFFE6)
                || cp == 0x2705 || cp == 0x2714 || cp == 0x2716 || cp == 0x274C
                || cp == 0x2B50 || cp == 0x2B55
                || (cp >= 0x1F300 && cp <= 0x1FAFF);
    }

    /**
     * 在当前行缓冲中寻找活跃的样式 ANSI（最近一次非 RESET 的样式码），用于硬折时在下一行重开。
     * 简化：寻找最后一个形如 ESC[3m/1m/32m/36m/4m/9m/90m 等且未被后续 RESET 关闭的序列。
     */
    public static String findActiveAnsi(String cur) {
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

    /** 按可见长度截断，保留 ANSI 序列，截断后若处于样式内则补 RESET 避免串色。 */
    public static String truncateVisible(String s, int width) {
        if (s == null) return "";
        int normalized = normalizeWidth(width);
        // width 已归一，但调用方可能期望按传入 width 截断；若传入极窄如 1-5，应按原值截断而非 80
        // 保持语义：若 width<=0 已在外层归一，此处按归一后宽度；否则按传入宽度
        int target = width <= 0 ? normalized : width;
        if (visibleLength(s) <= target) return s;
        StringBuilder out = new StringBuilder();
        int vis = 0;
        int i = 0;
        int n = s.length();
        while (i < n && vis < target) {
            int ansiEnd = findAnsiEnd(s, i);
            if (ansiEnd != -1) {
                out.append(s, i, ansiEnd + 1);
                i = ansiEnd + 1;
            } else {
                int cp = s.codePointAt(i);
                int cw = charWidth(cp);
                int end = i + Character.charCount(cp);
                out.append(s, i, end);
                vis += cw;
                i = end;
            }
        }
        String cur = out.toString();
        String active = findActiveAnsi(cur);
        if (active != null) cur = cur + Style.ANSI_RESET;
        return cur;
    }

    /** 将已带 ANSI 的单元格内容按可见宽度填补或截断至目标宽度。 */
    public static String padOrTruncateAnsi(String styled, int width) {
        if (styled == null) styled = "";
        int target = width <= 0 ? normalizeWidth(width) : width;
        int vis = visibleLength(styled);
        if (vis == target) return styled;
        if (vis < target) return styled + " ".repeat(target - vis);
        return truncateVisible(styled, target);
    }

    /**
     * ANSI 感知折行：按注入宽度折行，ANSI 码不计宽，超长不可断词硬折行。
     * <p>
     * 规则：优先在空格处断行；若单词本身超长则硬折；ANSI 序列不计宽且不被截断；
     * 折行后不保留行尾空格，硬折时若处于样式段内则补 RESET 并在下一行重开样式以保持视觉连续。
     * 纯函数实现，供段落/标题复用。与 {@link MarkdownRenderer} 共用。
     * </p>
     */
    public static String wrapAnsi(String text, int width) {
        if (text == null || text.isEmpty()) return text;
        int target = width <= 0 ? normalizeWidth(width) : width;
        // 当传入 width<=0 时已归一，但若文本可见长度 <= 归一宽度则直接返回
        if (visibleLength(text) <= target) return text;

        StringBuilder result = new StringBuilder();
        StringBuilder cur = new StringBuilder();
        int visible = 0;
        int lastSpacePos = -1;
        int visibleAtLastSpace = -1;

        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            int ansiEnd = findAnsiEnd(text, i);
            if (ansiEnd != -1) {
                String seq = text.substring(i, ansiEnd + 1);
                cur.append(seq);
                i = ansiEnd + 1;
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
                if (visible + 1 > target) {
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
                // 普通字符：按码点宽度累计（CJK/emoji 计 2）
                int cp = text.codePointAt(i);
                int cw = charWidth(cp);
                int cpEnd = i + Character.charCount(cp);
                if (visible + cw > target) {
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
                        cur.append(text, i, cpEnd);
                        visible += cw;
                        lastSpacePos = -1;
                        visibleAtLastSpace = -1;
                        i = cpEnd;
                    } else {
                        String active = findActiveAnsi(cur.toString());
                        if (active != null) result.append(cur).append(Style.ANSI_RESET).append('\n');
                        else result.append(cur).append('\n');
                        cur.setLength(0);
                        if (active != null) cur.append(active);
                        cur.append(text, i, cpEnd);
                        visible = cw;
                        lastSpacePos = -1;
                        visibleAtLastSpace = -1;
                        i = cpEnd;
                    }
                } else {
                    cur.append(text, i, cpEnd);
                    visible += cw;
                    i = cpEnd;
                }
            }
        }
        result.append(cur);
        return result.toString();
    }

    /**
     * 查找 ANSI 序列结束位置（'m'），若当前位置为 ESC[ 起始则返回 'm' 下标，否则 -1。
     */
    private static int findAnsiEnd(String s, int idx) {
        if (idx < 0 || idx + 1 >= s.length()) return -1;
        if (s.charAt(idx) != '\u001B' || s.charAt(idx + 1) != '[') return -1;
        int mIdx = s.indexOf('m', idx);
        if (mIdx == -1) return -1;
        return mIdx;
    }
}
