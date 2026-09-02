package dev.minicode.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * Mirrors pi-ai Message union: user | assistant | toolResult.
 * For MVP we keep a single class with role discriminator.
 */
public class Message {

    public enum Role { user, assistant, toolResult, system }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ToolCall {
        public String id;
        public String name;
        public Map<String, Object> arguments; // parsed JSON args

        // raw JSON string for round-trip
        public String argumentsJson;

        public ToolCall() {}
        public ToolCall(String id, String name, Map<String, Object> arguments, String argumentsJson) {
            this.id = id; this.name = name; this.arguments = arguments; this.argumentsJson = argumentsJson;
        }
    }

    public static class Content {
        public String type; // "text" | "toolCall" | "toolResult"
        public String text;
        public ToolCall toolCall;
        // for toolResult content
        public String toolCallId;
        public boolean isError;

        public static Content text(String t) {
            Content c = new Content(); c.type = "text"; c.text = t; return c;
        }
        public static Content toolCall(ToolCall tc) {
            Content c = new Content(); c.type = "toolCall"; c.toolCall = tc; return c;
        }
        public static Content toolResult(String toolCallId, String text, boolean isError) {
            Content c = new Content(); c.type = "toolResult"; c.toolCallId = toolCallId; c.text = text; c.isError = isError; return c;
        }
    }

    public Role role;
    public List<Content> content;
    public String stopReason; // "end" | "toolCalls" | "length" | "error" | "aborted"
    public String errorMessage;

    public Message() {}

    public Message(Role role, List<Content> content) {
        this.role = role;
        this.content = content;
    }

    // factories
    public static Message user(String text) {
        return new Message(Role.user, List.of(Content.text(text)));
    }

    public static Message system(String text) {
        return new Message(Role.system, List.of(Content.text(text)));
    }

    public static Message assistant(List<Content> content, String stopReason) {
        Message m = new Message(Role.assistant, content);
        m.stopReason = stopReason;
        return m;
    }

    public static Message toolResult(String toolCallId, String text, boolean isError) {
        return new Message(Role.toolResult, List.of(Content.toolResult(toolCallId, text, isError)));
    }

    public String text() {
        if (content == null) return "";
        StringBuilder sb = new StringBuilder();
        for (Content c : content) if (("text".equals(c.type) || "toolResult".equals(c.type)) && c.text != null) sb.append(c.text);
        return sb.toString();
    }

    public List<ToolCall> toolCalls() {
        if (content == null) return List.of();
        return content.stream()
                .filter(c -> "toolCall".equals(c.type) && c.toolCall != null)
                .map(c -> c.toolCall)
                .toList();
    }
}
