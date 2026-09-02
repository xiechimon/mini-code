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
        AgentEvent.ToolResultEvent {

    /** Agent 开始 */
    record AgentStart() implements AgentEvent {}
    /** Agent 结束，携带全部消息 */
    record AgentEnd(List<Message> messages) implements AgentEvent {}
    /** 轮次开始 */
    record TurnStart(int turn) implements AgentEvent {}
    /** 轮次结束 */
    record TurnEnd(Message assistant, List<Message> toolResults) implements AgentEvent {}
    /** 消息结束 */
    record MessageEnd(Message message) implements AgentEvent {}
    /** 工具开始执行 */
    record ToolStart(Message.ToolCall toolCall) implements AgentEvent {}
    /** 工具执行结果 */
    record ToolResultEvent(Message.ToolCall toolCall, String output, boolean isError) implements AgentEvent {}
}
