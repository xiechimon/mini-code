package dev.minicode.ai;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Resolves provider / model / baseUrl / apiKey from env + opencode auth.json fallback.
 * Mirrors pi-ai env-api-keys.ts + provider baseUrl logic.
 */
public class LlmConfig {

    public final Model model;
    public final String apiKey;

    public LlmConfig(Model model, String apiKey) {
        this.model = model;
        this.apiKey = apiKey;
    }

    public static LlmConfig resolve() {
        return resolve(System.getenv());
    }

    static LlmConfig resolve(Map<String, String> env) {
        // 1) explicit LLM_* overrides
        String provider = env.getOrDefault("LLM_PROVIDER", System.getProperty("llm.provider", "opencode-go"));
        String modelId = env.getOrDefault("LLM_MODEL", System.getProperty("llm.model", defaultModelFor(provider)));
        String baseUrl = env.getOrDefault("LLM_BASE_URL", System.getProperty("llm.baseUrl", null));
        String apiKey = env.getOrDefault("LLM_API_KEY", null);

        // 2) provider-specific env fallback (mirrors pi-ai env-api-keys.ts)
        if (apiKey == null) {
            apiKey = switch (provider) {
                case "opencode", "opencode-go" -> firstNonNull(env.get("OPENCODE_API_KEY"), tryReadOpencodeAuth(provider));
                case "deepseek" -> env.get("DEEPSEEK_API_KEY");
                case "openai" -> env.get("OPENAI_API_KEY");
                case "anthropic" -> firstNonNull(env.get("ANTHROPIC_API_KEY"), env.get("ANTHROPIC_AUTH_TOKEN"));
                case "minimax-cn" -> env.get("MINIMAX_CN_API_KEY");
                default -> null;
            };
        }

        if (baseUrl == null) {
            baseUrl = switch (provider) {
                case "opencode" -> "https://opencode.ai/zen/v1";
                case "opencode-go" -> "https://opencode.ai/zen/go/v1";
                case "deepseek" -> "https://api.deepseek.com/v1";
                case "openai" -> "https://api.openai.com/v1";
                case "minimax-cn" -> "https://api.minimax.chat/v1";
                default -> "https://api.openai.com/v1";
            };
        }

        // allow direct override via OPENCODE_BASE_URL etc
        if (provider.startsWith("opencode") && env.containsKey("OPENCODE_BASE_URL")) {
            baseUrl = env.get("OPENCODE_BASE_URL");
        }

        Model model = new Model(modelId, provider, baseUrl, "openai-completions");
        return new LlmConfig(model, apiKey);
    }

    private static String defaultModelFor(String provider) {
        return switch (provider) {
            case "opencode" -> "kimi-k2.6";
            case "opencode-go" -> "kimi-k2.6";
            case "deepseek" -> "deepseek-chat";
            case "openai" -> "gpt-4o-mini";
            case "minimax-cn" -> "MiniMax-M2.7";
            default -> "gpt-4o-mini";
        };
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }

    private static String tryReadOpencodeAuth(String provider) {
        try {
            // pi stores at ~/.local/share/opencode/auth.json (Linux/mac) — matches your machine
            String home = System.getProperty("user.home");
            Path p1 = Path.of(home, ".local", "share", "opencode", "auth.json");
            Path p2 = Path.of(home, ".config", "opencode", "auth.json");
            Path file = Files.exists(p1) ? p1 : Files.exists(p2) ? p2 : null;
            if (file == null) return null;
            String json = Files.readString(file);
            // very small parse without jackson to avoid circular dep
            // look for "opencode-go": {"key": "sk-..."}
            // fallback to opencode
            String key = extractJsonKey(json, provider);
            if (key == null && !"opencode".equals(provider)) key = extractJsonKey(json, "opencode");
            if (key == null) key = extractJsonKey(json, "opencode-go");
            return key;
        } catch (Exception e) {
            return null;
        }
    }

    private static String extractJsonKey(String json, String provider) {
        // naive: "provider": { ... "key": "sk-..." }
        int idx = json.indexOf("\"" + provider + "\"");
        if (idx < 0) return null;
        int keyIdx = json.indexOf("\"key\"", idx);
        if (keyIdx < 0) return null;
        int colon = json.indexOf(":", keyIdx);
        int q1 = json.indexOf("\"", colon);
        int q2 = json.indexOf("\"", q1 + 1);
        if (q1 < 0 || q2 < 0) return null;
        return json.substring(q1 + 1, q2);
    }
}
