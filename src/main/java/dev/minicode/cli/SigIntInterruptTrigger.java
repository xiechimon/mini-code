package dev.minicode.cli;

import dev.minicode.agent.InterruptTrigger;
import org.jline.utils.Signals;

/**
 * SIGINT(Ctrl-C) 中断触发器——将 OS 信号映射为 {@link InterruptTrigger} 的取消信号。
 * <p>
 * 经 JLine 的公开 {@link Signals} 注册 SIGINT：JLine 内部封装 JVM 的 {@code sun.misc.Signal}——
 * 二者同为「JDK 无可移植公共 SIGINT 捕获 API」时的事实标准（{@code Runtime.addShutdownHook} 会终止 JVM 且无法区分信号）。
 * 本实现走 JLine 而非直连 {@code sun.misc}，避免引用内部 API 触发编译警告。
 * </p>
 * <p>
 * 流式期间（AgentLoop 每回合）注册，触发器 {@code close()} 时恢复原处理器，保证结束后 Ctrl-C 恢复为
 * JLine 的弃行/退出语义。受限或非 Unix 环境下捕获异常则降级为永不取消，不影响主流程。
 * </p>
 * <p>
 * 对应 pi 的 abort 语义：在 {@code pi-coding-agent} 的交互模式（Node readline + Ink useInput）下，
 * SIGINT 与 Esc/Arrow 经同一个 keypress 事件管线分发；mini-code REPL 走 JLine 行编辑，二者均以
 * 「回合级 InterruptTrigger 抽象」统一抽象信号源，详见 docs/adr/0004。
 * </p>
 */
final class SigIntInterruptTrigger implements InterruptTrigger {

    private volatile boolean cancelled = false;
    private Object prev; // Signals.register 返回的恢复句柄

    SigIntInterruptTrigger() {
        try {
            prev = Signals.register("INT", () -> cancelled = true);
        } catch (Throwable ignore) {
            prev = null;
        }
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void close() {
        if (prev != null) {
            try {
                Signals.unregister("INT", prev);
            } catch (Throwable ignore) {
            }
            prev = null;
        }
    }
}
