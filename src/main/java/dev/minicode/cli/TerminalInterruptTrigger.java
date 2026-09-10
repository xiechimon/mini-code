package dev.minicode.cli;

import dev.minicode.agent.InterruptTrigger;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.utils.NonBlockingReader;
import org.jline.utils.Signals;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 终端感知的中断触发器——在 {@link SigIntInterruptTrigger} 的 Ctrl-C(SIGINT) 之上，叠加「按 Esc 也可中断」。
 * <p>
 * 背景：REPL 单轮流式期间终端处于 cooked 模式，内核行规程把 Esc(0x1B) 当普通字节缓冲到回车才交付，
 * 故 Esc 无法像 Ctrl-C(VINTR→SIGINT) 那样即时中断。要即时识别 Esc，必须在回合期间切到 raw 模式逐字节读取。
 * </p>
 * <p>
 * 做法：构造时（若 terminal 非空且支持）{@code enterRawMode()} 并起一个 daemon 观察线程轮询 {@code terminal.reader()}：
 * <ul>
 *   <li>读到裸 Esc——其后 {@link #ESC_WINDOW_MS} 内无后续字节（peek 超时/EOF）——判定为中断意图，置位 cancelled；</li>
 *   <li>读到 Esc 且紧跟后续字节（方向键 {@code ESC [ D}、Alt 组合键等转义序列）——吞掉整段序列，不中断；</li>
 *   <li>读到 Ctrl-C 字节 0x03（ISIG 关闭的极端环境下兜底）——置位 cancelled；正常 raw 模式下 Ctrl-C 仍由内核转 SIGINT，
 *       经 {@link Signals} 注册的处理器置位，与本类观察线程互为冗余；</li>
 *   <li>其他字节（含流式期间的 type-ahead）丢弃——避免污染下一轮 readLine。</li>
 * </ul>
 * {@code close()} 幂等：停观察线程 → 注销 SIGINT → {@code setAttributes} 还原进入 raw 前的属性，交还给 JLine 的下一次 readLine。
 * </p>
 * <p>
 * 降级：terminal 为 null（one-shot / Scanner 回退 / 管道）或 enterRawMode 失败时，仅保留 SIGINT 语义——
 * Ctrl-C 可中断，Esc 不可，不影响主流程。观察线程内任何异常都吞掉退出，绝不打断 AgentLoop。
 * </p>
 *
 * @see SigIntInterruptTrigger
 */
final class TerminalInterruptTrigger implements InterruptTrigger {

    /** 观察线程单次阻塞读超时（毫秒）——决定停止/close 的响应粒度，越小越灵敏但轮询越频繁 */
    private static final long POLL_MS = 100L;
    /** 裸 Esc 判定窗口（毫秒）——读到 Esc 后等待后续字节的最长时间；典型终端转义序列字节间隔远小于此 */
    private static final long ESC_WINDOW_MS = 40L;
    /** 转义序列收尾排空时的单字节等待（毫秒） */
    private static final long DRAIN_MS = 20L;

    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile boolean cancelled = false;
    private volatile boolean stopped = false;

    private final Terminal terminal;
    private Object prevSig;        // Signals.register 返回的恢复句柄
    private Attributes savedAttrs;   // enterRawMode 返回的原始终端属性，close 时还原
    private Thread watcher;          // raw 模式下的 Esc 观察线程；terminal 为 null 时为 null

    /**
     * @param terminal 交互终端；null 表示退化为仅 SIGINT（one-shot / Scanner 回退 / 管道）
     */
    TerminalInterruptTrigger(Terminal terminal) {
        this.terminal = terminal;
        try {
            prevSig = Signals.register("INT", () -> cancelled = true);
        } catch (Throwable ignore) {
            prevSig = null;
        }
        if (terminal != null) {
            try {
                savedAttrs = terminal.enterRawMode();
                Thread t = new Thread(this::watch, "mini-code esc-interrupt watcher");
                t.setDaemon(true);
                this.watcher = t;
                t.start();
            } catch (Throwable ignore) {
                // 终端不支持 raw 模式（如某些 dumb 终端）：退化为仅 SIGINT，Esc 不可中断
                savedAttrs = null;
                watcher = null;
            }
        }
    }

    /**
     * 观察循环：raw 模式下逐字节读取，识别裸 Esc / Ctrl-C 置位取消，转义序列整段吞掉。
     * 任何异常都静默退出——中断检测是增强项，绝不因它打断 AgentLoop。
     */
    private void watch() {
        try {
            NonBlockingReader r = terminal.reader();
            while (!stopped && !cancelled) {
                int c;
                try {
                    c = r.read(POLL_MS);
                } catch (java.io.IOException e) {
                    if (stopped) break;
                    continue; // 瞬时读失败（如被 interrupt 唤醒）：继续轮询
                }
                if (c == NonBlockingReader.READ_EXPIRED) continue;
                if (c == NonBlockingReader.EOF) break;
                if (c == 0x03) { // Ctrl-C 字节兜底（ISIG 关闭时）
                    cancelled = true;
                    break;
                }
                if (c == 0x1B) { // ESC
                    int p;
                    try {
                        p = r.peek(ESC_WINDOW_MS);
                    } catch (java.io.IOException e) {
                        if (stopped) break;
                        continue;
                    }
                    if (p == NonBlockingReader.READ_EXPIRED || p == NonBlockingReader.EOF) {
                        cancelled = true; // 裸 Esc：其后无字节，判定为中断意图
                        break;
                    }
                    drain(r); // 转义序列（方向键/Alt 组合）：吞掉后续字节，不中断
                }
                // 其他字节（含 type-ahead）丢弃
            }
        } catch (Throwable ignore) {
            // 观察线程不得让异常逃逸
        }
    }

    /** 排空一段转义序列的剩余字节，避免污染下一轮 readLine。 */
    private void drain(NonBlockingReader r) {
        while (!stopped) {
            int c;
            try {
                c = r.read(DRAIN_MS);
            } catch (java.io.IOException e) {
                return;
            }
            if (c == NonBlockingReader.READ_EXPIRED || c == NonBlockingReader.EOF) return;
        }
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return; // 幂等
        stopped = true;
        Thread w = watcher;
        if (w != null) {
            w.interrupt();
            try {
                w.join(POLL_MS + 200L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        if (prevSig != null) {
            try {
                Signals.unregister("INT", prevSig);
            } catch (Throwable ignore) {
            }
            prevSig = null;
        }
        if (terminal != null && savedAttrs != null) {
            try {
                terminal.setAttributes(savedAttrs);
            } catch (Throwable ignore) {
            }
            savedAttrs = null;
        }
    }
}
