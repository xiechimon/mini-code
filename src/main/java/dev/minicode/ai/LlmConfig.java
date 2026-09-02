package dev.minicode.ai;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 从环境变量 + .env 文件 + opencode auth.json 解析 provider / model / baseUrl / apiKey。
 * 对应 pi-ai 的 env-api-keys.ts 与 provider baseUrl 逻辑。
 */
public class LlmConfig {

    public final Model model;
    public final String apiKey;

    public LlmConfig(Model model, String apiKey) {
        this.model = model;
        this.apiKey = apiKey;
    }

    /** 使用当前进程环境解析配置 */
    public static LlmConfig resolve() {
        // 合并策略：.env 文件（向上查找） + 系统环境变量（覆盖 .env，符合标准 dotenv 优先级）
        Map<String, String> merged = new HashMap<>(Dotenv.load());
        merged.putAll(System.getenv());
        return resolve(merged);
    }

    static LlmConfig resolve(Map<String, String> env) {
        // 1) 显式的 LLM_* 覆盖
        String provider = env.getOrDefault("LLM_PROVIDER", System.getProperty("llm.provider", "opencode-go"));
        String modelId = env.getOrDefault("LLM_MODEL", System.getProperty("llm.model", defaultModelFor(provider)));
        String baseUrl = env.getOrDefault("LLM_BASE_URL", System.getProperty("llm.baseUrl", null));
        String apiKey = env.getOrDefault("LLM_API_KEY", null);

        // 2) 按 provider 查找对应的环境变量（对齐 pi-ai env-api-keys.ts）
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

        // 3) 按 provider 决定默认网关地址
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

        // 允许通过 OPENCODE_BASE_URL 直接覆盖 opencode 网关
        if (provider.startsWith("opencode") && env.containsKey("OPENCODE_BASE_URL")) {
            baseUrl = env.get("OPENCODE_BASE_URL");
        }

        Model model = new Model(modelId, provider, baseUrl, "openai-completions");
        return new LlmConfig(model, apiKey);
    }

    /** 各 provider 的默认模型 */
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

    /** 尝试从 opencode 的 auth.json 读取 key（兼容本机路径） */
    private static String tryReadOpencodeAuth(String provider) {
        try {
            String home = System.getProperty("user.home");
            Path p1 = Path.of(home, ".local", "share", "opencode", "auth.json");
            Path p2 = Path.of(home, ".config", "opencode", "auth.json");
            Path file = Files.exists(p1) ? p1 : Files.exists(p2) ? p2 : null;
            if (file == null) return null;
            String json = Files.readString(file);
            // 极简解析：查找 "provider": { ... "key": "sk-..." }
            String key = extractJsonKey(json, provider);
            if (key == null && !"opencode".equals(provider)) key = extractJsonKey(json, "opencode");
            if (key == null) key = extractJsonKey(json, "opencode-go");
            return key;
        } catch (Exception e) {
            return null;
        }
    }

    /** 从 JSON 文本中抠出指定 provider 的 key */
    private static String extractJsonKey(String json, String provider) {
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
