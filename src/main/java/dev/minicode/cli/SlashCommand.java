package dev.minicode.cli;

/**
 * 斜杠命令：会话上下文上的一次操作。对齐 pi 的 builtin slash command（wiki/4）。
 * <p>
 * 命令实现应为 {@link ReplContext} 上的纯操作：输出经 {@code ctx.out()}，状态经 ctx 读写，
 * 不碰终端/静态单例——这是本特性的唯一测试缝。
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
