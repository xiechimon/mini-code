package dev.minicode.ai;

import java.util.List;

/**
 * Mirrors pi-ai Context: systemPrompt + messages + tools.
 */
public class Context {
    public String systemPrompt;
    public List<Message> messages;
    public List<Tool> tools;

    public Context(String systemPrompt, List<Message> messages, List<Tool> tools) {
        this.systemPrompt = systemPrompt;
        this.messages = messages;
        this.tools = tools;
    }
}
