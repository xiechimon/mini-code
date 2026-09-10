package dev.minicode.agent;

import dev.minicode.ai.Message;

import java.util.Map;

/**
 * beforeToolCall 钩子的载荷：当前工具调用 + 其生效参数。
 * <p>
 * 对齐 pi wiki/19 的 {@code tool_call} 事件——pi 的 input 是可变引用（钩子就地改 map 即生效），
 * mini-code 用不可变 record 载荷 + 「替换整个 event」实现同等的改写语义：MODIFY 时由 AgentLoop
 * 用新的 {@link Message.ToolCall} 重建 event 再交给后续钩子，钩子无需也不应持有跨调用引用。
 * 有意简化见 {@code docs/adr/0005}。
 * </p>
 *
 * @param toolCall  当前工具调用（经前序钩子 MODIFY 后即替换版本）
 * @param arguments 当前生效参数（通常与 {@code toolCall.arguments} 同源）
 */
public record ToolCallEvent(Message.ToolCall toolCall, Map<String, Object> arguments) {
}
