package dev.minicode.cli;

import java.time.Duration;

/**
 * 轮末统计数据：耗时 + 轮数 + 工具次数，收敛原 render(Duration,int,int) 的 Data Clumps。
 * <p>
 * 对应 spec 轮末统计行「N 轮 · M 次工具 · X.Xs」；耗时由调用方计时后传入，计数由事件流累计后注入，渲染器不碰时钟。
 * 提供显式工厂 {@link #of(Duration, int, int)} 与推断工厂 {@link #inferred(Duration)}，将旧 -1 哨兵封装在 record 内，
 * 调用方不再手写 -1。内部仍以 -1 作为推断哨兵，但语义通过工厂方法显式表达，便于将来改为 Optional/显式字段而不改调用点。
 * </p>
 * <p>
 * 对齐 pi 源：pi-tui 渲染层轮末统计的耗时注入模式（保持渲染器纯函数）。
 * </p>
 */
public record TurnStats(Duration elapsed, int turns, int toolCalls) {

    /** 显式计数：调用方已累计好轮数与工具次数。 */
    public static TurnStats of(Duration elapsed, int turns, int toolCalls) {
        return new TurnStats(elapsed, turns, toolCalls);
    }

    /** 推断计数：未显式注入时由渲染器从 AgentEnd 消息推断（旧 -1 哨兵的显式表达）。 */
    public static TurnStats inferred(Duration elapsed) {
        return new TurnStats(elapsed, -1, -1);
    }

    /** 是否需要推断轮数（内部 -1 哨兵）。 */
    public boolean inferTurns() {
        return turns < 0;
    }

    /** 是否需要推断工具次数（内部 -1 哨兵）。 */
    public boolean inferTools() {
        return toolCalls < 0;
    }

    /** 归一化耗时（null → ZERO），便于格式化。 */
    public Duration elapsedOrZero() {
        return elapsed != null ? elapsed : Duration.ZERO;
    }

    /** 快捷：零值统计。 */
    public static TurnStats zero() {
        return new TurnStats(Duration.ZERO, 0, 0);
    }
}
