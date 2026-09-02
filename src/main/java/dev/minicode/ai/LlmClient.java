package dev.minicode.ai;

import java.util.concurrent.CancellationException;

/**
 * StreamFn equivalent for Java.
 * Contract: must NOT throw for model/runtime errors — encode failure as Message with stopReason=error.
 * We use non-streaming MVP first (single AssistantMessage), but keep interface async-friendly.
 */
public interface LlmClient {
    /**
     * @param model   target model
     * @param context context with systemPrompt + messages + tools
     * @return assistant message (may contain toolCalls)
     * @throws CancellationException if aborted
     */
    Message chat(Model model, Context context) throws Exception;

    static LlmClient fake(java.util.function.BiFunction<Model, Context, Message> fn) {
        return (m, c) -> fn.apply(m, c);
    }
}
