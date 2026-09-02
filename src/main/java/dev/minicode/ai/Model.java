package dev.minicode.ai;

/**
 * 最小化的模型描述，对应 pi-ai 中的 Model。
 * MVP 阶段仅保留核心字段。
 * provider 决定 baseUrl 与鉴权方式。
 */
public record Model(
        String id,
        String provider,
        String baseUrl,
        String api // "openai-completions" | "anthropic-messages" 等，MVP 仅用 openai-completions
) {
    /** 通用创建方法 */
    public static Model of(String provider, String id, String baseUrl) {
        return new Model(id, provider, baseUrl, "openai-completions");
    }

    /** opencode-go 模型，网关地址对应 pi 中的 https://opencode.ai/zen/go/v1 */
    public static Model opencodeGo(String modelId) {
        return new Model(modelId, "opencode-go", "https://opencode.ai/zen/go/v1", "openai-completions");
    }

    /** opencode (Zen) 模型 */
    public static Model opencode(String modelId) {
        return new Model(modelId, "opencode", "https://opencode.ai/zen/v1", "openai-completions");
    }

    /** DeepSeek 模型 */
    public static Model deepseek(String modelId) {
        return new Model(modelId, "deepseek", "https://api.deepseek.com/v1", "openai-completions");
    }
}
