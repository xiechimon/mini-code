package dev.minicode.agent;

/**
 * 工具调用前后钩子。
 * <p>
 * 对齐 pi wiki/19 的 {@code beforeToolCall} / {@code afterToolCall}：before 返回 {@link ToolDecision}
 * 三档（放行 / 阻断 / 改参），after 仅观测 {@link AgentEvent.ToolResultEvent}（不重写结果）。同步链、
 * 按注册顺序票决，BLOCK 短路后续 before 钩子，钩子自身异常 = 该工具 isError 失败。
 * 有意简化（terminate / afterToolCall 改写 / registry 动态装卸 / execute signal）见 {@code docs/adr/0005}。
 * </p>
 * 钩子实现者需自知：before/after 未来可能在并行组内被并发调用（per-tool 独立票决），详见 ADR-0005。
 */
public interface ToolHook {

    /**
     * 工具执行前钩子，默认放行。
     *
     * @param event 当前调用与生效参数
     * @return 三档决定；返回 null 等价放行
     */
    default ToolDecision beforeToolCall(ToolCallEvent event) {
        return ToolDecision.proceed();
    }

    /**
     * 工具执行后（或被阻断后）观测钩子，默认无操作，不重写结果。
     */
    default void afterToolCall(AgentEvent.ToolResultEvent event) {
    }
}
