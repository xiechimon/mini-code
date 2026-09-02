package dev.minicode.agent;

import dev.minicode.ai.Message;
import java.util.List;

public sealed interface AgentEvent permits
        AgentEvent.AgentStart,
        AgentEvent.AgentEnd,
        AgentEvent.TurnStart,
        AgentEvent.TurnEnd,
        AgentEvent.MessageEnd,
        AgentEvent.ToolStart,
        AgentEvent.ToolResultEvent {

    record AgentStart() implements AgentEvent {}
    record AgentEnd(List<Message> messages) implements AgentEvent {}
    record TurnStart(int turn) implements AgentEvent {}
    record TurnEnd(Message assistant, List<Message> toolResults) implements AgentEvent {}
    record MessageEnd(Message message) implements AgentEvent {}
    record ToolStart(Message.ToolCall toolCall) implements AgentEvent {}
    record ToolResultEvent(Message.ToolCall toolCall, String output, boolean isError) implements AgentEvent {}
}
