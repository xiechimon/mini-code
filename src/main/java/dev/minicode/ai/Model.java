package dev.minicode.ai;

/**
 * Minimal Model descriptor, mirrors pi-ai Model<Api> but trimmed for MVP.
 * Provider determines baseUrl/auth handling.
 */
public record Model(
        String id,
        String provider,
        String baseUrl,
        String api // "openai-completions" | "anthropic-messages" etc — MVP only uses openai-completions
) {
    public static Model of(String provider, String id, String baseUrl) {
        return new Model(id, provider, baseUrl, "openai-completions");
    }

    public static Model opencodeGo(String modelId) {
        // pi's opencode-go baseUrl = https://opencode.ai/zen/go/v1 (see openai-completions-retry.test)
        return new Model(modelId, "opencode-go", "https://opencode.ai/zen/go/v1", "openai-completions");
    }

    public static Model opencode(String modelId) {
        return new Model(modelId, "opencode", "https://opencode.ai/zen/v1", "openai-completions");
    }

    public static Model deepseek(String modelId) {
        return new Model(modelId, "deepseek", "https://api.deepseek.com/v1", "openai-completions");
    }
}
