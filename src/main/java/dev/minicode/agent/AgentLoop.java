package dev.minicode.agent;

import dev.minicode.ai.Context;
import dev.minicode.ai.LlmClient;
import dev.minicode.ai.Message;
import dev.minicode.ai.Model;
import dev.minicode.tools.ToolDefinition;
import dev.minicode.tools.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 最小 Agent 循环，忠实对齐 pi 的 packages/agent/src/agent-loop.ts。
 * 简化版：单层循环，不含 steering/followUp，工具顺序执行。
 * 通过 EventSink 对外发射事件，便于观测与后续 TUI 接入。
 */
public class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);

    /** 事件回调 */
    public interface EventSink {
        void on(AgentEvent e);
    }

    private final LlmClient llm;
    private final Model model;
    private final String systemPrompt;
    private final List<ToolDefinition> tools;
    private final int maxTurns; // 最大轮次，防止无限循环

    public AgentLoop(LlmClient llm, Model model, String systemPrompt, List<ToolDefinition> tools, int maxTurns) {
        this.llm = llm;
        this.model = model;
        this.systemPrompt = systemPrompt;
        this.tools = tools;
        this.maxTurns = maxTurns;
    }

    /**
     * 执行 Agent 循环
     * @param initialPrompts 初始用户提示
     * @param sink 事件接收器（可为空）
     * @return 包含初始提示在内的全部消息
     */
    public List<Message> run(List<Message> initialPrompts, EventSink sink) throws Exception {
        List<Message> contextMessages = new ArrayList<>(initialPrompts);
        List<Message> newMessages = new ArrayList<>(initialPrompts);

        if (sink != null) sink.on(new AgentEvent.AgentStart());
        int turn = 0;
        while (turn < maxTurns) {
            turn++;
            if (sink != null) sink.on(new AgentEvent.TurnStart(turn));

            Context ctx = new Context(systemPrompt, List.copyOf(contextMessages),
                    tools.stream().map(ToolDefinition::toLlmTool).collect(Collectors.toList()));

            Message assistant = llm.chat(model, ctx);
            // 归一化空值
            if (assistant.content == null) assistant.content = List.of();
            if (assistant.stopReason == null) assistant.stopReason = "end";

            contextMessages.add(assistant);
            newMessages.add(assistant);
            if (sink != null) sink.on(new AgentEvent.MessageEnd(assistant));

            // 遇到错误/中断直接结束
            if ("error".equals(assistant.stopReason) || "aborted".equals(assistant.stopReason)) {
                if (sink != null) sink.on(new AgentEvent.TurnEnd(assistant, List.of()));
                if (sink != null) sink.on(new AgentEvent.AgentEnd(newMessages));
                return newMessages;
            }

            List<Message.ToolCall> toolCalls = assistant.toolCalls();
            if (toolCalls.isEmpty()) {
                // 无工具调用，说明任务已完成
                if (sink != null) sink.on(new AgentEvent.TurnEnd(assistant, List.of()));
                if (sink != null) sink.on(new AgentEvent.AgentEnd(newMessages));
                return newMessages;
            }

            // 被截断（length）：全部工具调用视为失败，对齐 pi 的处理
            if ("length".equals(assistant.stopReason)) {
                List<Message> results = new ArrayList<>();
                for (Message.ToolCall tc : toolCalls) {
                    String err = "工具调用因 token 超限被截断（stopReason=length），参数可能不完整。";
                    Message r = Message.toolResult(tc.id, err, true);
                    results.add(r);
                    contextMessages.add(r);
                    newMessages.add(r);
                    if (sink != null) sink.on(new AgentEvent.ToolResultEvent(tc, err, true));
                }
                if (sink != null) sink.on(new AgentEvent.TurnEnd(assistant, results));
                continue; // 让模型看到错误后重试
            }

            // 顺序执行工具（MVP），MVP2 再加并行
            List<Message> toolResults = new ArrayList<>();
            for (Message.ToolCall tc : toolCalls) {
                ToolDefinition def = findTool(tc.name);
                String output;
                boolean isError;
                if (def == null) {
                    output = "未知工具: " + tc.name;
                    isError = true;
                } else {
                    if (sink != null) sink.on(new AgentEvent.ToolStart(tc));
                    try {
                        Map<String, Object> args = tc.arguments != null ? tc.arguments : Map.of();
                        ToolResult r = def.execute(tc.id, args);
                        output = r.content();
                        isError = r.isError();
                    } catch (Exception e) {
                        output = "工具执行失败: " + e.getMessage();
                        isError = true;
                        log.warn("工具 {} 执行失败", tc.name, e);
                    }
                    if (sink != null) sink.on(new AgentEvent.ToolResultEvent(tc, output, isError));
                }
                Message resultMsg = Message.toolResult(tc.id, output, isError);
                toolResults.add(resultMsg);
                contextMessages.add(resultMsg);
                newMessages.add(resultMsg);
            }

            if (sink != null) sink.on(new AgentEvent.TurnEnd(assistant, toolResults));
            // 带上工具结果进入下一轮
        }

        if (sink != null) sink.on(new AgentEvent.AgentEnd(newMessages));
        return newMessages;
    }

    private ToolDefinition findTool(String name) {
        for (ToolDefinition t : tools) if (t.name().equals(name)) return t;
        return null;
    }
}
