package dev.minicode.cli;

import dev.minicode.agent.InterruptTrigger;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.utils.NonBlockingReader;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 终端感知的中断触发器——在 {@link SigIntInterruptTrigger}（Ctrl-C / SIGINT）之上，叠加「按 Esc 也可中断」，
 * 并通过 {@code typeAheadSink} 把回合期间被吞掉的可打印字节回吐给 {@code LineReader.runMacro}，
 * 避免 raw 模式引入「流式期间提前输入丢失」的回归。
 * <p>
 * <strong>为什么需要 raw 模式 + 观察线程</strong>：REPL 单轮流式期间终端处于 cooked 模式，
 * 内核行规程把 Esc(0x1B) 当普通字节缓冲到回车才交付，故 Esc 无法像 Ctrl-C(VINTR→SIGINT) 那样即时中断。
 * 要即时识别 Esc，必须在回合期间切到 raw 模式逐字节读取。
 * </p>
 * <p>
 * <strong>观察线程循环</strong>（{@link #watch()}）：
 * <ul>
 *   <li>读到裸 Esc——其后 {@link #ESC_WINDOW_MS} 内无后续字节（peek 超时/EOF）——判定为中断意图，置位 cancelled；</li>
 *   <li>读到 Esc 且紧跟后续字节（方向键 {@code ESC [ D}、Alt 组合键等转义序列）——吞掉整段序列，不中断；</li>
 *   <li>读到 Ctrl-C 字节 0x03（ISIG 关闭的极端环境下兜底）——置位 cancelled；正常 raw 模式下 Ctrl-C 仍由
 *       内核转 SIGINT，经 {@link SigIntInterruptTrigger} 置位，与本类观察线程互为冗余；</li>
 *   <li>其他字节（含流式期间的 type-ahead）——写入内部 buffer，{@code close()} 时经 sink 一次性回吐，
 *       避免污染下一轮 readLine 又不丢失用户已敲内容。</li>
 * </ul>
 * </p>
 * <p>
 * {@code close()} 幂等：停观察线程 → 委托 {@link SigIntInterruptTrigger#close()} 注销 SIGINT →
 * {@code setAttributes} 还原进入 raw 前的属性 → 把缓冲的 type-ahead 经 sink 推回。
 * </p>
 * <p>
 * <strong>降级</strong>：terminal 为 null（one-shot / Scanner 回退 / 管道）或 enterRawMode 失败时，
 * 退化为只走 {@link SigIntInterruptTrigger}——Ctrl-C 可中断，Esc/type-ahead 不可用，不影响主流程。
 * 观察线程内任何异常都吞掉退出，绝不打断 AgentLoop。
 * </p>
 * <p>
 * 对应 pi-coding-agent interactive mode 的 abort 语义：pi 在 TUI 下用 Ink {@code useInput} 把 ESC/Ctrl-C
 * 转为 abort 事件；mini-code 的 REPL 走 JLine 行编辑 + raw 模式观察线程，等价实现的「回合级 InterruptTrigger 抽象」。
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
    private volatile boolean watcherCancelled = false;
    private volatile boolean stopped = false;

    private final Terminal terminal;
    private final Consumer<String> typeAheadSink; // type-ahead 回吐口（通常 = reader::runMacro），null 表示丢弃
    private final SigIntInterruptTrigger sigint = new SigIntInterruptTrigger();
    private final StringBuilder typeAhead = new StringBuilder(); // 仅 watcher 单线程追加，close() 在 finally 后读，无需同步

    private Attributes savedAttrs;   // enterRawMode 返回的原始终端属性，close 时还原
    private Thread watcher;          // raw 模式下的 Esc 观察线程；terminal 为 null 时为 null

    /**
     * @param terminal      交互终端；null 表示退化为仅 SIGINT（one-shot / Scanner 回退 / 管道）
     * @param typeAheadSink 回合期间被吞掉的可打印字节的回收口；通常传 {@code lineReader::runMacro} 让
     *                      下一轮 readLine 把这些字符当作已键入处理（含光标位置与回显）；null 表示丢弃
     */
    TerminalInterruptTrigger(Terminal terminal, Consumer<String> typeAheadSink) {
        this.terminal = terminal;
        this.typeAheadSink = typeAheadSink;
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
     * 观察循环：raw 模式下逐字节读取，识别裸 Esc / Ctrl-C 置位取消，转义序列整段吞掉，
     * 可打印字节入 type-ahead 缓冲等 close 时回吐。
     * 任何异常都静默退出——中断检测是增强项，绝不因它打断 AgentLoop。
     */
    private void watch() {
        try {
            NonBlockingReader r = terminal.reader();
            while (!stopped && !watcherCancelled) {
                int c = readQuietly(r, POLL_MS);
                if (c == NonBlockingReader.READ_EXPIRED) continue;
                if (c == NonBlockingReader.EOF) break;
                if (c == 0x03) { // Ctrl-C 字节兜底（ISIG 关闭时）
                    watcherCancelled = true;
                    break;
                }
                if (c == 0x1B) { // ESC
                    int p = peekQuietly(r, ESC_WINDOW_MS);
                    if (p == NonBlockingReader.READ_EXPIRED || p == NonBlockingReader.EOF) {
                        watcherCancelled = true; // 裸 Esc：其后无字节，判定为中断意图
                        break;
                    }
                    drain(r); // 转义序列（方向键/Alt 组合）：吞掉后续字节，不中断
                    continue;
                }
                // 其他字节（含 type-ahead）追加到缓冲，close() 时经 sink 回吐
                typeAhead.append((char) c);
            }
        } catch (Throwable ignore) {
            // 观察线程不得让异常逃逸
        }
    }

    /** 转义序列收尾排空：读到 READ_EXPIRED/EOF 视为序列结束；期间异常静默返回。 */
    private void drain(NonBlockingReader r) {
        while (!stopped) {
            int c = readQuietly(r, DRAIN_MS);
            if (c == NonBlockingReader.READ_EXPIRED || c == NonBlockingReader.EOF) return;
        }
    }

    /** 静默读取：异常时若在 stop 流程中则返 EOF 退出，否则返 READ_EXPIRED 让外层继续轮询。 */
    private int readQuietly(NonBlockingReader r, long timeoutMs) {
        try {
            return r.read(timeoutMs);
        } catch (IOException e) {
            return stopped ? NonBlockingReader.EOF : NonBlockingReader.READ_EXPIRED;
        }
    }

    /** 静默 peek：异常时若在 stop 流程中则返 EOF 退出，否则返 READ_EXPIRED 让外层继续。 */
    private int peekQuietly(NonBlockingReader r, long timeoutMs) {
        try {
            return r.peek(timeoutMs);
        } catch (IOException e) {
            return stopped ? NonBlockingReader.EOF : NonBlockingReader.READ_EXPIRED;
        }
    }

    @Override
    public boolean isCancelled() {
        return watcherCancelled || sigint.isCancelled();
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
        sigint.close();
        if (terminal != null && savedAttrs != null) {
            try {
                terminal.setAttributes(savedAttrs);
            } catch (Throwable ignore) {
            }
            savedAttrs = null;
        }
        // type-ahead 回吐放在最末：watcher 已停，buffer 不再被追加，可一次性交付
        if (typeAheadSink != null && typeAhead.length() > 0) {
            try {
                typeAheadSink.accept(typeAhead.toString());
            } catch (Throwable ignore) {
            }
            typeAhead.setLength(0);
        }
    }
}
