package dev.minicode.tools;

public record ToolResult(
        String content,
        boolean isError,
        Object details // optional structured details, mirrors pi details
) {
    public static ToolResult ok(String content) {
        return new ToolResult(content, false, null);
    }
    public static ToolResult ok(String content, Object details) {
        return new ToolResult(content, false, details);
    }
    public static ToolResult error(String content) {
        return new ToolResult(content, true, null);
    }
}
