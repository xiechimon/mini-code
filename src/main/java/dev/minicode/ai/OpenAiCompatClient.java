package dev.minicode.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容的 HTTP 客户端（MVP 非流式版本）。
 * 负责把 Context 转为 OpenAI Chat Completions 请求，解析助手回复与工具调用。
 * 对应 pi-ai/src/api/openai-completions.ts 的简化版（去掉了流式与重试）。
 */
public class OpenAiCompatClient implements LlmClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final HttpClient http;
    private final String apiKeyOverride; // 可为空，为空时从 LlmConfig 解析

    public OpenAiCompatClient() {
        this(null);
    }

    public OpenAiCompatClient(String apiKeyOverride) {
        this.apiKeyOverride = apiKeyOverride;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public Message chat(Model model, Context context) throws Exception {
        // 解析 API Key
        String apiKey = apiKeyOverride != null ? apiKeyOverride : resolveApiKey(model);
        if (apiKey == null || apiKey.isBlank()) {
            Message m = new Message();
            m.role = Message.Role.assistant;
            m.content = List.of(Message.Content.text("[mini-code] 缺少 provider " + model.provider() + " 的 API Key，请设置 " + envVarFor(model.provider()) + " 或 OPENCODE_API_KEY"));
            m.stopReason = "error";
            m.errorMessage = "缺少 API Key";
            return m;
        }

        // 构造请求体
        ObjectNode payload = buildPayload(model, context);
        String url = model.baseUrl().replaceAll("/$", "") + "/chat/completions";
        String body = MAPPER.writeValueAsString(payload);

        // 带重试的发送：对 500/502/503/429 重试一次（对齐 pi 的 retryProviderRequest）
        HttpResponse<String> resp = null;
        int maxAttempts = 2;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(120))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("User-Agent", "mini-code/0.1")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            int code = resp.statusCode();
            // 500 系与 429 视为可重试
            boolean retryable = code == 500 || code == 502 || code == 503 || code == 429;
            if (!retryable || attempt == maxAttempts) break;
            // 轻量退避
            try {
                Thread.sleep(1000L * attempt);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        assert resp != null;
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            String hint = buildErrorHint(resp.statusCode(), resp.body(), model);
            Message m = new Message();
            m.role = Message.Role.assistant;
            m.content = List.of(Message.Content.text("[mini-code] 大模型请求失败 " + resp.statusCode() + ": " + truncate(resp.body(), 1500) + hint));
            m.stopReason = "error";
            m.errorMessage = resp.body();
            return m;
        }

        return parseResponse(resp.body());
    }

    /**
     * 针对常见错误码给出可操作的中文提示。
     * 500 对 muse-spark 等模型常为网关侧临时不可用，给出切回 kimi 的建议。
     */
    String buildErrorHint(int status, String body, Model model) {
        String lower = body != null ? body.toLowerCase() : "";
        if (status == 500 && lower.contains("internal server error")) {
            if (model.id().contains("muse-spark")) {
                return "\n\n[提示] 模型 " + model.id() + " 在网关侧暂时不可用（500 Internal server error），非 mini-code 代码问题。" +
                        "\n建议：1) 稍后重试  2) 切回可用模型：LLM_MODEL=kimi-k2.6  3) 或用 .env 设 LLM_MODEL=kimi-k2.6";
            }
            return "\n\n[提示] 网关 500，通常为模型侧临时故障，稍后重试或切换模型。";
        }
        if (status == 401 || lower.contains("auth")) {
            return "\n\n[提示] 鉴权失败，请检查 " + envVarFor(model.provider()) + " 是否正确。";
        }
        if (lower.contains("credits") || lower.contains("insufficient")) {
            return "\n\n[提示] 余额不足，请到 https://opencode.ai/workspace 充值或切换到免费模型。";
        }
        if (lower.contains("not supported")) {
            return "\n\n[提示] 模型 " + model.id() + " 不被当前 provider 支持，请检查 LLM_PROVIDER 与 LLM_MODEL 是否匹配（opencode-go 用 muse-spark-1.2-contributor，opencode 用 muse-spark-1.2-contributor-free）。";
        }
        return "";
    }

    /**
     * 按模型 provider 解析对应的 API Key —— 复用 LlmConfig 的单一映射表，避免在两处维护 env var 名称
     */
    private String resolveApiKey(Model model) {
        LlmConfig cfg = LlmConfig.resolve();
        if (cfg.model().provider().equals(model.provider()) && cfg.apiKey() != null) return cfg.apiKey();
        Map<String, String> env = System.getenv();
        // 单一真实来源：LlmConfig.apiKeyForProvider
        String direct = LlmConfig.apiKeyForProvider(model.provider(), env, null);
        return direct != null ? direct : cfg.apiKey();
    }

    private String envVarFor(String provider) {
        return switch (provider) {
            case "opencode", "opencode-go" -> "OPENCODE_API_KEY";
            case "deepseek" -> "DEEPSEEK_API_KEY";
            case "openai" -> "OPENAI_API_KEY";
            default -> "LLM_API_KEY";
        };
    }

    /**
     * 构造 OpenAI Chat Completions 请求体
     */
    ObjectNode buildPayload(Model model, Context context) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", model.id());
        root.put("stream", false);

        ArrayNode messages = MAPPER.createArrayNode();

        // 系统提示词
        if (context.systemPrompt != null && !context.systemPrompt.isBlank()) {
            ObjectNode sys = MAPPER.createObjectNode();
            sys.put("role", "system");
            sys.put("content", context.systemPrompt);
            messages.add(sys);
        }

        // 历史消息
        for (Message m : context.messages) {
            switch (m.role) {
                case user -> {
                    ObjectNode o = MAPPER.createObjectNode();
                    o.put("role", "user");
                    o.put("content", m.text());
                    messages.add(o);
                }
                case assistant -> {
                    ObjectNode o = MAPPER.createObjectNode();
                    o.put("role", "assistant");
                    List<Message.ToolCall> tcs = m.toolCalls();
                    if (!tcs.isEmpty()) {
                        // 有工具调用时，content 可能为空（OpenAI 规范）
                        String txt = m.text();
                        if (!txt.isBlank()) o.put("content", txt);
                        else o.putNull("content");
                        ArrayNode tca = MAPPER.createArrayNode();
                        for (Message.ToolCall tc : tcs) {
                            ObjectNode tco = MAPPER.createObjectNode();
                            tco.put("id", tc.id);
                            tco.put("type", "function");
                            ObjectNode fn = MAPPER.createObjectNode();
                            fn.put("name", tc.name);
                            fn.put("arguments", tc.argumentsJson != null ? tc.argumentsJson : "{}");
                            tco.set("function", fn);
                            tca.add(tco);
                        }
                        o.set("tool_calls", tca);
                    } else {
                        o.put("content", m.text());
                    }
                    messages.add(o);
                }
                case toolResult -> {
                    // 每条工具结果拆成一条 role=tool 的消息
                    for (Message.Content c : m.content) {
                        if ("toolResult".equals(c.type)) {
                            ObjectNode o = MAPPER.createObjectNode();
                            o.put("role", "tool");
                            o.put("tool_call_id", c.toolCallId);
                            o.put("content", c.text != null ? c.text : "");
                            messages.add(o);
                        }
                    }
                }
                case system -> {
                    ObjectNode o = MAPPER.createObjectNode();
                    o.put("role", "system");
                    o.put("content", m.text());
                    messages.add(o);
                }
            }
        }

        root.set("messages", messages);

        // 工具定义
        if (context.tools != null && !context.tools.isEmpty()) {
            ArrayNode tools = MAPPER.createArrayNode();
            for (Tool t : context.tools) {
                ObjectNode to = MAPPER.createObjectNode();
                to.put("type", "function");
                ObjectNode fn = MAPPER.createObjectNode();
                fn.put("name", t.name());
                fn.put("description", t.description());
                fn.set("parameters", t.parameters());
                to.set("function", fn);
                tools.add(to);
            }
            root.set("tools", tools);
            root.put("tool_choice", "auto");
        }

        return root;
    }

    /**
     * 解析 OpenAI 响应为 Message
     */
    Message parseResponse(String json) throws Exception {
        JsonNode root = MAPPER.readTree(json);
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            Message m = new Message();
            m.role = Message.Role.assistant;
            m.content = List.of(Message.Content.text("[mini-code] 响应中无 choices: " + truncate(json, 1000)));
            m.stopReason = "error";
            return m;
        }
        JsonNode choice = choices.get(0);
        JsonNode msg = choice.path("message");
        String finishReason = choice.path("finish_reason").asText("stop");
        String contentText = msg.path("content").isNull() ? "" : msg.path("content").asText("");

        List<Message.Content> contents = new ArrayList<>();
        if (contentText != null && !contentText.isBlank()) {
            contents.add(Message.Content.text(contentText));
        }

        JsonNode toolCalls = msg.path("tool_calls");
        String stopReason = "end";
        if (toolCalls.isArray() && !toolCalls.isEmpty()) {
            for (JsonNode tc : toolCalls) {
                String id = tc.path("id").asText();
                JsonNode fn = tc.path("function");
                String name = fn.path("name").asText();
                String argsJson = fn.path("arguments").asText("{}");
                Map<String, Object> args = new HashMap<>();
                try {
                    JsonNode argsNode = MAPPER.readTree(argsJson);
                    argsNode.fields().forEachRemaining(e -> {
                        JsonNode v = e.getValue();
                        if (v.isTextual()) args.put(e.getKey(), v.asText());
                        else if (v.isNumber()) args.put(e.getKey(), v.numberValue());
                        else if (v.isBoolean()) args.put(e.getKey(), v.asBoolean());
                        else args.put(e.getKey(), v.toString());
                    });
                } catch (Exception ignore) {
                }
                Message.ToolCall call = new Message.ToolCall(id, name, args, argsJson);
                contents.add(Message.Content.toolCall(call));
            }
            stopReason = "toolCalls";
            if ("length".equals(finishReason)) stopReason = "length";
        } else {
            if ("length".equals(finishReason)) stopReason = "length";
            else if ("tool_calls".equals(finishReason)) stopReason = "toolCalls";
        }
        if (contents.isEmpty()) contents.add(Message.Content.text(""));

        Message out = new Message();
        out.role = Message.Role.assistant;
        out.content = contents;
        out.stopReason = stopReason;
        return out;
    }

    private String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }
}
