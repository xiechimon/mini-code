package dev.minicode.ai;

import java.util.concurrent.CancellationException;

/**
 * 大模型客户端接口，对应 pi 中的 StreamFn。
 * 约定：模型/运行时错误不得抛异常，必须编码为 stopReason=error 的 Message 返回。
 * MVP 阶段先用非流式（单条 AssistantMessage），接口保留异步扩展能力。
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
}
