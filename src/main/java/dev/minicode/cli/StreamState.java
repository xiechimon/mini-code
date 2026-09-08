package dev.minicode.cli;

/**
 * 流式会话状态对象——持有宽度感知的行数记账，由调用方（Main）持有并传引用给渲染器。
 * 保持渲染器纯函数缝不破坏：状态由调用方持有，渲染为纯函数（输入 state + 事件 → 输出文本 + 新 state）。
 * 对应 spec 的回退重绘与首片段覆盖；宽度感知复用 AnsiTextUtil 的可见宽度计算。
 * <p>
 * 行数计算：按注入宽度对累积文本做字符级折行（终端真实行为，非单词级），CJK/宽字符计 2 列，
 * 换行符切分为逻辑行，每行按 ceil(可见长度/宽度) 计物理行。尾随换行不计额外行（光标已在下一行行首），
 * 空行计 1 行，确保 CUU 精确回退到流式块起点。
 * </p>
 */
public class StreamState {

    private final int width;
    private final StringBuilder buffer = new StringBuilder();
    private int rows = 0;
    private boolean hasStreamed = false;

    public StreamState(int width) {
        this.width = AnsiTextUtil.normalizeWidth(width);
    }

    public int width() {
        return width;
    }

    public int rows() {
        return rows;
    }

    public boolean hasStreamed() {
        return hasStreamed;
    }

    public String buffer() {
        return buffer.toString();
    }

    public void reset() {
        buffer.setLength(0);
        rows = 0;
        hasStreamed = false;
    }

    /**
     * 追加流式片段并更新行数记账。
     */
    public void append(String delta) {
        if (delta == null || delta.isEmpty()) return;
        buffer.append(delta);
        hasStreamed = true;
        rows = computeRows(buffer.toString(), width);
    }

    /**
     * 宽度感知的行数计算——纯函数，供单测直接断言。
     *
     * @param text 文本（可含换行，不含 ANSI 时等价可见长度）
     * @param width 可用宽度（已归一，<=0 按 80）
     * @return 终端物理行数
     */
    public static int computeRows(String text, int width) {
        if (text == null || text.isEmpty()) return 0;
        int w = AnsiTextUtil.normalizeWidth(width);
        // 保留尾随空段以识别换行导致的逻辑行
        String[] lines = text.split("\n", -1);
        int total = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            // 尾随换行产生的末尾空段不计额外行（光标已在下一行行首，内容行已在前一段计过）
            boolean isTrailingEmpty = (i == lines.length - 1 && line.isEmpty() && text.endsWith("\n"));
            if (isTrailingEmpty) continue;
            int vis = AnsiTextUtil.visibleLength(line);
            if (vis == 0) {
                total += 1; // 空逻辑行仍占 1 物理行
            } else {
                total += (vis + w - 1) / w; // ceil
            }
        }
        return total;
    }
}
