package dev.minicode.agent;

/**
 * 中断触发器抽象——极小接口，置位即请求取消。
 * 对应 spec 的“可注入中断触发器抽象”，将 OS 信号等不可测性隔离在实现之外。
 * <p>
 * 设计：单方法 {@link #isCancelled()} 透传给 {@link dev.minicode.ai.LlmClient#stream} 的取消参数；
 * 继承 {@link AutoCloseable} 以支持“每次流前获取、完成后释放”的生命周期（CLI 的 SIGINT 句柄在 close 时注销）。
 * 默认 close 为空操作，简单实现（AtomicBoolean）无需释放。
 * </p>
 * 本票只做抽象与 AgentLoop 接线，信号源由 04 票在 CLI 层注入。
 */
public interface InterruptTrigger extends AutoCloseable {

    /**
     * 是否已请求取消。
     *
     * @return true 表示应中断当前流式请求
     */
    boolean isCancelled();

    /**
     * 释放触发器资源，默认空操作。
     * CLI 的 SIGINT 触发器在此注销信号处理器，恢复原语义。
     */
    @Override
    default void close() {
    }
}
