package dev.minicode.agent;

import dev.minicode.ai.Message;

import java.util.List;

/**
 * Agent 事件，用于对外暴露执行过程（后续 TUI 可订阅）。
 * 对应 pi 中的 AgentEvent。
 */
public sealed interface AgentEvent permits
        AgentEvent.AgentStart,
        AgentEvent.AgentEnd,
        AgentEvent.TurnStart,
        AgentEvent.TurnEnd,
        AgentEvent.MessageStart,
        AgentEvent.MessageEnd,
        AgentEvent.MessageUpdate,
        AgentEvent.ToolStart,
        AgentEvent.ToolResultEvent {

    /**
     * Agent 开始
     */
    record AgentStart() implements AgentEvent {
    }

    /**
     * Agent 结束，携带全部消息
     */
    record AgentEnd(List<Message> messages) implements AgentEvent {
    }

    /**
     * 轮次开始
     */
    record TurnStart(int turn) implements AgentEvent {
    }

    /**
     * 轮次结束
     */
    record TurnEnd(Message assistant, List<Message> toolResults) implements AgentEvent {
    }

    /**
     * 消息开始——一条助手指令消息的生命周期起点（对齐 pi 的 message_start）。
     * 在首个增量之前发射；配合 {@link MessageUpdate}（逐片段增量）与 {@link MessageEnd}（最终完整消息）
     * 构成消息生命周期。仅流式期间发射；管道同步路径零流式事件，只有 MessageEnd。
     */
    record MessageStart() implements AgentEvent {
    }

    /**
     * 消息结束
     */
    record MessageEnd(Message message) implements AgentEvent {
    }

    /**
     * 流式增量——携带一次文本片段（对齐 pi 的 message_update，原 StreamDelta 更名）。
     * 对应 CONTEXT.md 的“流式增量”术语；仅在流式期间逐片段发射。
     * MessageEnd 语义不变（携带最终完整 Message）。
     *
     * @param delta 文本片段（非空时原样直出，终态由 MessageEnd 的完整渲染替换）
     */
    record MessageUpdate(String delta) implements AgentEvent {
    }

    /**
     * 工具开始执行
     */
    record ToolStart(Message.ToolCall toolCall) implements AgentEvent {
    }

    /**
     * 工具执行结果
     */
    record ToolResultEvent(Message.ToolCall toolCall, String output, boolean isError) implements AgentEvent {
    }
}
