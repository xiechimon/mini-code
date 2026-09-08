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

    /** 可见长度（剥离 ANSI 后）。 */
    public static int visibleLength(String s) {
        if (s == null) return 0;
        return stripAnsi(s).length();
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
            char c = s.charAt(i);
            int ansiEnd = findAnsiEnd(s, i);
            if (ansiEnd != -1) {
                out.append(s, i, ansiEnd + 1);
                i = ansiEnd + 1;
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
                if (visible + 1 > target) {
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
