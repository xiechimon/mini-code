package dev.minicode.ai;

import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 大模型客户端接口，对应 pi 中的 StreamFn。
 * 约定：模型/运行时错误不得抛异常，必须编码为 stopReason=error 的 Message 返回。
 * MVP 阶段先用非流式（单条 AssistantMessage），接口保留异步扩展能力。
 * 流式扩展以 default 方法提供，向后兼容：默认委派同步方法且回调零次。
 */
public interface LlmClient {
    /**
     * 快捷创建假客户端，用于测试
     */
    static LlmClient fake(java.util.function.BiFunction<Model, Context, Message> fn) {
        return fn::apply;
    }

    /**
     * 发起一次对话
     *
     * @param model   目标模型
     * @param context 上下文（系统提示词 + 历史消息 + 工具）
     * @return 助手消息（可能包含工具调用）
     * @throws CancellationException 中断时抛出
     */
    Message chat(Model model, Context context) throws Exception;

    /**
     * 流式对话——逐片段回调文本增量，最终返回完整 Message。
     * 默认实现委派同步方法且不产生回调，保持既有 fake 测试零改动。
     *
     * @param model   目标模型
     * @param context 上下文
     * @param onDelta 文本片段回调（可能为 null）
     * @return 完整助手消息（文本累积 + 工具调用拼装）
     */
    default Message stream(Model model, Context context, Consumer<String> onDelta) throws Exception {
        return stream(model, context, onDelta, () -> false);
    }

    /**
     * 可取消的流式对话。
     * isCancelled 置位时应及时中断读取并返回已收部分（由实现方轮询）。
     * 默认实现忽略取消信号，委派无取消的重载。
     *
     * @param model       目标模型
     * @param context     上下文
     * @param onDelta     文本片段回调
     * @param isCancelled 取消信号（返回 true 即请求中断）
     * @return 完整或半截助手消息
     */
    default Message stream(Model model, Context context, Consumer<String> onDelta, Supplier<Boolean> isCancelled) throws Exception {
        return chat(model, context);
    }
}
