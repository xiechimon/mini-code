package dev.minicode.tools;

/**
 * 工具执行结果
 *
 * @param content 文本内容
 * @param isError 是否为错误
 * @param details 可选的结构化详情（对齐 pi 的 details）
 */
public record ToolResult(
        String content,
        boolean isError,
        Object details
) {
    /**
     * 成功结果
     */
    public static ToolResult ok(String content) {
        return new ToolResult(content, false, null);
    }

    public static ToolResult ok(String content, Object details) {
        return new ToolResult(content, false, details);
    }

    /**
     * 失败结果
     */
    public static ToolResult error(String content) {
        return new ToolResult(content, true, null);
    }
}
