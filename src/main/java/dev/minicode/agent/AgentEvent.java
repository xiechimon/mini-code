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
        AgentEvent.MessageEnd,
        AgentEvent.ToolStart,
        AgentEvent.ToolResultEvent,
        AgentEvent.StreamDelta {

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
     * 消息结束
     */
    record MessageEnd(Message message) implements AgentEvent {
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

    /**
     * 流式增量——携带一次文本片段（又名 MessageDelta，见 PR 草稿）。
     * 对应 spec 的“流式增量”与 CONTEXT.md 的 Stream Delta 术语；协议扩展已获规格授权。
     * MessageEnd 语义不变（携带最终完整 Message），本事件仅在流式期间逐片段发射。
     *
     * @param delta 文本片段（非空时原样直出，终态由 MessageEnd 的完整渲染替换）
     */
    record StreamDelta(String delta) implements AgentEvent {
    }
}
