package dev.minicode.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 测试用 {@link ToolHook}：按调用顺序记录 before/after 序列，并持有最近一次 beforeToolCall 的载荷，
 * 决定由构造注入的决策函数给出（默认全部 {@link ToolDecision#proceed()}）。
 * before/after 异常经决策函数或匿名子类注入，用于钩子故障测试。跨工具钩子相关测试复用。
 */
public class RecordingHook implements ToolHook {

    /** 调用序列：{@code before:<toolName>} / {@code after:<toolName>}，按真实调用顺序追加。 */
    public final List<String> sequence = new ArrayList<>();

    /** 最近一次 beforeToolCall 收到的载荷（链式 MODIFY 测试断言后续钩子看到修改后的调用）。 */
    public volatile ToolCallEvent lastEvent;

    private final Function<ToolCallEvent, ToolDecision> beforeFn;

    /** 默认：全部放行。 */
    public RecordingHook() {
        this(e -> ToolDecision.proceed());
    }

    /** 自定义 beforeToolCall 决定（可在内部抛异常以测钩子故障）。 */
    public RecordingHook(Function<ToolCallEvent, ToolDecision> beforeFn) {
        this.beforeFn = beforeFn;
    }

    @Override
    public ToolDecision beforeToolCall(ToolCallEvent event) {
        sequence.add("before:" + event.toolCall().name);
        lastEvent = event;
        return beforeFn.apply(event);
    }

    @Override
    public void afterToolCall(AgentEvent.ToolResultEvent event) {
        sequence.add("after:" + event.toolCall().name);
    }
}
