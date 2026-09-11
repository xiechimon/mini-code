package dev.minicode.cli;

/**
 * 对齐 {@code pi/pi-coding-agent/interactive-mode.ts}：单条内置斜杠命令的行为契约——会话上下文上的一次操作。
 * <p>
 * 命令实现应为 {@link ReplContext} 上的纯操作：输出经 {@code ctx.out()}，状态经 ctx 读写，
 * 不碰终端/静态单例——这是本特性的唯一测试缝。见 docs/wiki/4、docs/adr/0006。
 * </p>
 */
@FunctionalInterface
public interface SlashCommand {

    /**
     * 执行命令。
     *
     * @param args 命令名之后、首个空白后的余串（已 trim，可为空串）
     * @param ctx  REPL 会话上下文
     */
    void execute(String args, ReplContext ctx) throws Exception;
}
