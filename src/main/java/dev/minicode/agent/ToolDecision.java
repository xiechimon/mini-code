package dev.minicode.agent;

import dev.minicode.ai.Message;

/**
 * beforeToolCall 钩子的三档决定。
 * <p>
 * 对齐 pi wiki/19 的「放行 / 阻断 / 改参」三类能力（有意省略 terminate，见 {@code docs/adr/0005}）：
 * <ul>
 *   <li>{@link Action#PROCEED} —— 放行，工具正常执行；</li>
 *   <li>{@link Action#BLOCK} —— 门禁，跳过该工具，{@code reason} 作为 isError 文本回 LLM；</li>
 *   <li>{@link Action#MODIFY} —— 用 {@code modifiedCall} 替换当前 ToolCall，继续走后续钩子（后续钩子看到修改后的）。</li>
 * </ul>
 *
 * @param action       决定档
 * @param reason       BLOCK 时的拒绝原因（PROCEED/MODIFY 可为 null）
 * @param modifiedCall MODIFY 时的替换调用（其余档可为 null）
 */
public record ToolDecision(Action action, String reason, Message.ToolCall modifiedCall) {

    /** 决定档 */
    public enum Action {
        PROCEED, BLOCK, MODIFY
    }

    /** 放行 */
    public static ToolDecision proceed() {
        return new ToolDecision(Action.PROCEED, null, null);
    }

    /** 阻断，reason 作为工具失败文本回 LLM */
    public static ToolDecision block(String reason) {
        return new ToolDecision(Action.BLOCK, reason, null);
    }

    /** 改参：用 newCall 替换当前 ToolCall，后续钩子看到替换版本 */
    public static ToolDecision modify(Message.ToolCall newCall) {
        return new ToolDecision(Action.MODIFY, null, newCall);
    }
}
