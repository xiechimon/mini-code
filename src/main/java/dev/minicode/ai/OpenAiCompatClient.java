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

        // 构造请求体并发送
        ObjectNode payload = buildPayload(model, context);
        String url = model.baseUrl().replaceAll("/$", "") + "/chat/completions";
        String body = MAPPER.writeValueAsString(payload);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .header("User-Agent", "mini-code/0.1")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            Message m = new Message();
            m.role = Message.Role.assistant;
            m.content = List.of(Message.Content.text("[mini-code] 大模型请求失败 " + resp.statusCode() + ": " + truncate(resp.body(), 2000)));
            m.stopReason = "error";
            m.errorMessage = resp.body();
            return m;
        }

        return parseResponse(resp.body());
    }

    /** 按模型 provider 解析对应的 API Key */
    private String resolveApiKey(Model model) {
        LlmConfig cfg = LlmConfig.resolve();
        if (cfg.model.provider().equals(model.provider()) && cfg.apiKey != null) return cfg.apiKey;
        Map<String, String> env = System.getenv();
        return switch (model.provider()) {
            case "opencode", "opencode-go" -> firstNonNull(env.get("OPENCODE_API_KEY"), cfg.apiKey);
            case "deepseek" -> env.get("DEEPSEEK_API_KEY");
            case "openai" -> env.get("OPENAI_API_KEY");
            default -> cfg.apiKey;
        };
    }

    private String envVarFor(String provider) {
        return switch (provider) {
            case "opencode", "opencode-go" -> "OPENCODE_API_KEY";
            case "deepseek" -> "DEEPSEEK_API_KEY";
            case "openai" -> "OPENAI_API_KEY";
            default -> "LLM_API_KEY";
        };
    }

    private String firstNonNull(String a, String b) { return a != null ? a : b; }

    /** 构造 OpenAI Chat Completions 请求体 */
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

    /** 解析 OpenAI 响应为 Message */
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
                } catch (Exception ignore) {}
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
