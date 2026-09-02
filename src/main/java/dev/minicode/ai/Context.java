package dev.minicode.ai;

import java.util.List;

/**
 * 对话上下文，对应 pi-ai 中的 Context。
 * 包含系统提示词、历史消息、可用工具三要素。
 */
public class Context {
    public String systemPrompt;      // 系统提示词
    public List<Message> messages;   // 历史消息
    public List<Tool> tools;         // 可用工具

    public Context(String systemPrompt, List<Message> messages, List<Tool> tools) {
        this.systemPrompt = systemPrompt;
        this.messages = messages;
        this.tools = tools;
    }
}
