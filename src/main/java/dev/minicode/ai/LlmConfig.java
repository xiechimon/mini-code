package dev.minicode.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 从环境变量 + .env 文件 + opencode auth.json 解析 provider / model / baseUrl / apiKey。
 * 对应 pi-ai 的 env-api-keys.ts 与 provider baseUrl 逻辑。
 */
public record LlmConfig(Model model, String apiKey) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 使用当前进程环境解析配置
     */
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
                case "opencode", "opencode-go" ->
                        firstNonNull(env.get("OPENCODE_API_KEY"), tryReadOpencodeAuth(provider));
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

    /**
     * 各 provider 的默认模型
     */
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

    /**
     * 按 provider 查询对应的环境变量中的 API Key，供 OpenAiCompatClient 复用，避免两处维护同一张映射表
     */
    static String apiKeyForProvider(String provider, Map<String, String> env, String fallback) {
        String key = switch (provider) {
            case "opencode", "opencode-go" -> firstNonNull(env.get("OPENCODE_API_KEY"), fallback);
            case "deepseek" -> env.get("DEEPSEEK_API_KEY");
            case "openai" -> env.get("OPENAI_API_KEY");
            case "anthropic" -> firstNonNull(env.get("ANTHROPIC_API_KEY"), env.get("ANTHROPIC_AUTH_TOKEN"));
            case "minimax-cn" -> env.get("MINIMAX_CN_API_KEY");
            default -> null;
        };
        return key != null ? key : fallback;
    }

    /**
     * 尝试从 opencode 的 auth.json 读取 key（兼容本机路径）—— 使用 Jackson 解析，避免字符串 indexOf 的脆弱性
     */
    private static String tryReadOpencodeAuth(String provider) {
        try {
            String home = System.getProperty("user.home");
            Path p1 = Path.of(home, ".local", "share", "opencode", "auth.json");
            Path p2 = Path.of(home, ".config", "opencode", "auth.json");
            Path file = Files.exists(p1) ? p1 : Files.exists(p2) ? p2 : null;
            if (file == null) return null;
            JsonNode root = MAPPER.readTree(Files.readString(file));
            // 优先精确匹配 provider，其次回退到 opencode / opencode-go
            String key = extractKeyFromNode(root, provider);
            if (key == null && !"opencode".equals(provider)) key = extractKeyFromNode(root, "opencode");
            if (key == null) key = extractKeyFromNode(root, "opencode-go");
            return key;
        } catch (Exception e) {
            return null;
        }
    }

    private static String extractKeyFromNode(JsonNode root, String provider) {
        JsonNode node = root.path(provider);
        if (node.isMissingNode() || node.isNull()) return null;
        // 兼容两种结构：{ "opencode-go": { "key": "sk-xxx" } } 或 { "opencode-go": "sk-xxx" }
        if (node.isTextual()) return node.asText();
        JsonNode keyNode = node.path("key");
        if (keyNode.isTextual()) return keyNode.asText();
        // 有些版本嵌套为 { "opencode-go": { "apiKey": "..." } }
        JsonNode apiKeyNode = node.path("apiKey");
        if (apiKeyNode.isTextual()) return apiKeyNode.asText();
        return null;
    }
}
