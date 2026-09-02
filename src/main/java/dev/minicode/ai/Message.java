package dev.minicode.ai;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * 消息模型，对应 pi-ai 中的 Message 联合类型：user | assistant | toolResult。
 * MVP 阶段用单一类 + role 区分的方式简化实现。
 */
public class Message {

    /**
     * 消息角色
     */
    public enum Role {user, assistant, toolResult, system}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ToolCall {
        public String id;              // 工具调用 ID（对应 OpenAI tool_call_id）
        public String name;            // 工具名
        public Map<String, Object> arguments; // 解析后的参数

        // 保留原始 JSON 字符串，用于回传给模型
        public String argumentsJson;

        public ToolCall() {
        }

        public ToolCall(String id, String name, Map<String, Object> arguments, String argumentsJson) {
            this.id = id;
            this.name = name;
            this.arguments = arguments;
            this.argumentsJson = argumentsJson;
        }
    }

    /**
     * 内容块：文本 / 工具调用 / 工具结果
     */
    public static class Content {
        public String type; // "text" | "toolCall" | "toolResult"
        public String text;
        public ToolCall toolCall;
        // 工具结果专用字段
        public String toolCallId;
        public boolean isError;

        /**
         * 创建文本块
         */
        public static Content text(String t) {
            Content c = new Content();
            c.type = "text";
            c.text = t;
            return c;
        }

        /**
         * 创建工具调用块
         */
        public static Content toolCall(ToolCall tc) {
            Content c = new Content();
            c.type = "toolCall";
            c.toolCall = tc;
            return c;
        }

        /**
         * 创建工具结果块
         */
        public static Content toolResult(String toolCallId, String text, boolean isError) {
            Content c = new Content();
            c.type = "toolResult";
            c.toolCallId = toolCallId;
            c.text = text;
            c.isError = isError;
            return c;
        }
    }

    public Role role;              // 角色
    public List<Content> content;  // 内容块列表
    public String stopReason;      // 结束原因："end" | "toolCalls" | "length" | "error" | "aborted"
    public String errorMessage;    // 错误信息（当 stopReason=error 时）

    public Message() {
    }

    public Message(Role role, List<Content> content) {
        this.role = role;
        this.content = content;
    }

    // ===== 快捷构造方法 =====

    /**
     * 用户消息
     */
    public static Message user(String text) {
        return new Message(Role.user, List.of(Content.text(text)));
    }

    /**
     * 系统消息
     */
    public static Message system(String text) {
        return new Message(Role.system, List.of(Content.text(text)));
    }

    /**
     * 助手消息
     */
    public static Message assistant(List<Content> content, String stopReason) {
        Message m = new Message(Role.assistant, content);
        m.stopReason = stopReason;
        return m;
    }

    /**
     * 工具结果消息
     */
    public static Message toolResult(String toolCallId, String text, boolean isError) {
        return new Message(Role.toolResult, List.of(Content.toolResult(toolCallId, text, isError)));
    }

    /**
     * 提取所有文本内容（包含工具结果的文本）
     */
    public String text() {
        if (content == null) return "";
        StringBuilder sb = new StringBuilder();
        for (Content c : content)
            if (("text".equals(c.type) || "toolResult".equals(c.type)) && c.text != null) sb.append(c.text);
        return sb.toString();
    }

    /**
     * 提取所有工具调用
     */
    public List<ToolCall> toolCalls() {
        if (content == null) return List.of();
        return content.stream()
                .filter(c -> "toolCall".equals(c.type) && c.toolCall != null)
                .map(c -> c.toolCall)
                .toList();
    }
}
