package dev.minicode.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * OpenAI 兼容的 HTTP 客户端（MVP 非流式版本 + 流式扩展）。
 * 负责把 Context 转为 OpenAI Chat Completions 请求，解析助手回复与工具调用。
 * 对应 pi-ai/src/api/openai-completions.ts 的简化版，扩展了流式与回退。
 * <p>
 * 流式：请求体加 stream 标记，JDK HttpClient sendAsync + InputStream 逐行喂给 {@link SseParser}，
 * 文本增量逐次回调，工具调用 delta 累积拼装为完整 ToolCall；建连失败/解析异常回退一次同步调用并记日志；
 * 外部取消信号及时中断读取并返回已收部分。
 */
public class OpenAiCompatClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final HttpClient http;
    private final String apiKeyOverride; // 可为空，为空时从 LlmConfig 解析
    private final String sessionId; // opencode 网关路由用：每会话稳定，对齐 pi provider-attribution.ts

    public OpenAiCompatClient() {
        this(null);
    }

    public OpenAiCompatClient(String apiKeyOverride) {
        this(apiKeyOverride, resolveDefaultSessionId());
    }

    public OpenAiCompatClient(String apiKeyOverride, String sessionIdOverride) {
        this.apiKeyOverride = apiKeyOverride;
        this.sessionId = sessionIdOverride != null && !sessionIdOverride.isBlank()
                ? sessionIdOverride
                : resolveDefaultSessionId();
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
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(120))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("User-Agent", "mini-code/0.1");
            // opencode 网关要求 x-opencode-session（见 https://opencode.ai/docs/go/#where-can-i-use-it），
            // 对齐 pi provider-attribution.ts：仅对 opencode 系目标发送，会话内稳定以便路由与 prompt caching。
            if (isOpencodeTarget(model)) {
                builder.header("x-opencode-session", sessionId);
                builder.header("x-opencode-client", "mini-code");
            }
            HttpRequest req = builder.POST(HttpRequest.BodyPublishers.ofString(body)).build();
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

    // ===== 流式扩展 =====

    @Override
    public Message stream(Model model, Context context, Consumer<String> onDelta) throws Exception {
        return stream(model, context, onDelta, () -> false);
    }

    @Override
    public Message stream(Model model, Context context, Consumer<String> onDelta, Supplier<Boolean> isCancelled) throws Exception {
        Supplier<Boolean> cancel = isCancelled != null ? isCancelled : () -> false;
        Consumer<String> cb = onDelta != null ? onDelta : s -> {
        };
        try {
            return doStream(model, context, cb, cancel);
        } catch (CancellationException | InterruptedException e) {
            // 取消导致的提前退出——返回已收部分（此处尚无累积，返回空 aborted）
            if (cancel.get() || Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                Message m = new Message();
                m.role = Message.Role.assistant;
                m.content = List.of(Message.Content.text(""));
                m.stopReason = "aborted";
                return m;
            }
            throw e;
        } catch (Exception e) {
            // 取消不应走回退
            if (cancel.get() || Thread.currentThread().isInterrupted()) {
                Message m = new Message();
                m.role = Message.Role.assistant;
                m.content = List.of(Message.Content.text(""));
                m.stopReason = "aborted";
                return m;
            }
            log.warn("流式请求失败，回退为非流式: {}", e.toString(), e);
            try {
                return chat(model, context);
            } catch (Exception fallbackEx) {
                log.warn("回退的非流式请求也失败: {}", fallbackEx.toString(), fallbackEx);
                Message m = new Message();
                m.role = Message.Role.assistant;
                m.content = List.of(Message.Content.text("[mini-code] 流式与回退均失败: " + fallbackEx.getMessage()));
                m.stopReason = "error";
                m.errorMessage = fallbackEx.getMessage();
                return m;
            }
        }
    }

    /**
     * 真正的流式实现——请求体加 stream 标记，异步 HTTP + 行流喂解析器。
     * 注意：解析器吃行序列，按行切分时保留 SSE 行边界（BufferedReader.readLine 已提供行边界）。
     */
    private Message doStream(Model model, Context context, Consumer<String> onDelta, Supplier<Boolean> isCancelled) throws Exception {
        if (isCancelled.get() || Thread.currentThread().isInterrupted()) {
            return buildStreamMessage(new StringBuilder(), new TreeMap<>(), null, true);
        }

        String apiKey = apiKeyOverride != null ? apiKeyOverride : resolveApiKey(model);
        if (apiKey == null || apiKey.isBlank()) {
            Message m = new Message();
            m.role = Message.Role.assistant;
            m.content = List.of(Message.Content.text("[mini-code] 缺少 provider " + model.provider() + " 的 API Key，请设置 " + envVarFor(model.provider()) + " 或 OPENCODE_API_KEY"));
            m.stopReason = "error";
            m.errorMessage = "缺少 API Key";
            return m;
        }

        ObjectNode payload = buildPayload(model, context);
        payload.put("stream", true);
        String url = model.baseUrl().replaceAll("/$", "") + "/chat/completions";
        String body = MAPPER.writeValueAsString(payload);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .header("User-Agent", "mini-code/0.1");
        if (isOpencodeTarget(model)) {
            builder.header("x-opencode-session", sessionId);
            builder.header("x-opencode-client", "mini-code");
        }
        HttpRequest req = builder.POST(HttpRequest.BodyPublishers.ofString(body)).build();

        CompletableFuture<HttpResponse<InputStream>> future = http.sendAsync(req, HttpResponse.BodyHandlers.ofInputStream());

        // 取消监护线程：轮询 isCancelled，置位时取消 future 并关闭流以中断阻塞读取
        AtomicBoolean finished = new AtomicBoolean(false);
        AtomicReference<InputStream> streamRef = new AtomicReference<>();
        Thread monitor = new Thread(() -> {
            while (!finished.get()) {
                if (isCancelled.get() || Thread.currentThread().isInterrupted()) {
                    CompletableFuture<HttpResponse<InputStream>> f = future;
                    if (f != null && !f.isDone()) {
                        f.cancel(true);
                    }
                    InputStream s = streamRef.get();
                    if (s != null) {
                        try {
                            s.close();
                        } catch (IOException ignore) {
                        }
                    }
                    break;
                }
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
        monitor.setDaemon(true);
        monitor.start();

        HttpResponse<InputStream> resp;
        try {
            resp = future.get();
        } catch (CancellationException ce) {
            finished.set(true);
            monitor.interrupt();
            if (isCancelled.get() || Thread.currentThread().isInterrupted()) {
                return buildStreamMessage(new StringBuilder(), new TreeMap<>(), null, true);
            }
            throw new IOException("流式建连已取消", ce);
        } catch (ExecutionException ee) {
            finished.set(true);
            monitor.interrupt();
            if (isCancelled.get() || Thread.currentThread().isInterrupted()) {
                return buildStreamMessage(new StringBuilder(), new TreeMap<>(), null, true);
            }
            Throwable cause = ee.getCause();
            throw new IOException("流式建连失败: " + (cause != null ? cause.getMessage() : ee.getMessage()), cause != null ? cause : ee);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            finished.set(true);
            monitor.interrupt();
            future.cancel(true);
            if (isCancelled.get() || Thread.currentThread().isInterrupted()) {
                return buildStreamMessage(new StringBuilder(), new TreeMap<>(), null, true);
            }
            throw ie;
        }

        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            String errorBody = "";
            try (InputStream err = resp.body()) {
                if (err != null) errorBody = new String(err.readAllBytes(), StandardCharsets.UTF_8);
            } catch (Exception ignore) {
            }
            finished.set(true);
            monitor.interrupt();
            log.warn("流式请求返回非 2xx {}，回退为非流式，body: {}", resp.statusCode(), truncate(errorBody, 500));
            throw new IOException("流式响应状态异常: " + resp.statusCode() + " body: " + truncate(errorBody, 500));
        }

        InputStream is = resp.body();
        streamRef.set(is);
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));

        StringBuilder textAccum = new StringBuilder();
        Map<Integer, ToolAccum> toolMap = new TreeMap<>();
        String[] lastFinishHolder = new String[1];
        List<String> eventLines = new ArrayList<>();
        boolean doneSeen = false;

        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (isCancelled.get() || Thread.currentThread().isInterrupted()) {
                    break;
                }
                eventLines.add(line);
                if (line.isEmpty()) {
                    if (doneSeen) {
                        eventLines.clear();
                        continue;
                    }
                    boolean hasData = false;
                    for (String l : eventLines) {
                        if (l.startsWith("data:")) {
                            hasData = true;
                            break;
                        }
                    }
                    if (!hasData) {
                        // 纯注释/空行事件，无需解析
                        eventLines.clear();
                        continue;
                    }
                    List<SseParser.Event> evs;
                    try {
                        evs = SseParser.parse(new ArrayList<>(eventLines));
                    } catch (Exception parseEx) {
                        throw new IOException("SSE 解析异常", parseEx);
                    }
                    boolean hasDoneMarkerInThisEvent = false;
                    for (SseParser.Event ev : evs) {
                        if (ev instanceof SseParser.TextDelta td) {
                            textAccum.append(td.text());
                            try {
                                onDelta.accept(td.text());
                            } catch (Exception cbEx) {
                                log.debug("onDelta 回调异常: {}", cbEx.toString());
                            }
                        } else if (ev instanceof SseParser.ToolCallDelta tcd) {
                            int idx = tcd.index();
                            ToolAccum acc = toolMap.computeIfAbsent(idx, k -> new ToolAccum());
                            if (tcd.id() != null) acc.id = tcd.id();
                            if (tcd.name() != null) acc.name = tcd.name();
                            if (tcd.argumentsDelta() != null) acc.args.append(tcd.argumentsDelta());
                        } else if (ev instanceof SseParser.Done d) {
                            if ("[DONE]".equals(d.raw())) {
                                hasDoneMarkerInThisEvent = true;
                                doneSeen = true;
                            } else {
                                lastFinishHolder[0] = d.raw();
                            }
                        }
                    }
                    eventLines.clear();
                    if (hasDoneMarkerInThisEvent) {
                        break;
                    }
                    if (doneSeen) break;
                    if (isCancelled.get() || Thread.currentThread().isInterrupted()) break;
                }
            }
            // 末尾未以空行结束的残留事件（少见，但需 flush）
            if (!eventLines.isEmpty() && !doneSeen) {
                boolean hasData = false;
                for (String l : eventLines) if (l.startsWith("data:")) { hasData = true; break; }
                if (hasData) {
                    List<SseParser.Event> evs = SseParser.parse(new ArrayList<>(eventLines));
                    for (SseParser.Event ev : evs) {
                        if (ev instanceof SseParser.TextDelta td) {
                            textAccum.append(td.text());
                            try { onDelta.accept(td.text()); } catch (Exception ignore) {}
                        } else if (ev instanceof SseParser.ToolCallDelta tcd) {
                            int idx = tcd.index();
                            ToolAccum acc = toolMap.computeIfAbsent(idx, k -> new ToolAccum());
                            if (tcd.id() != null) acc.id = tcd.id();
                            if (tcd.name() != null) acc.name = tcd.name();
                            if (tcd.argumentsDelta() != null) acc.args.append(tcd.argumentsDelta());
                        } else if (ev instanceof SseParser.Done d) {
                            if (!"[DONE]".equals(d.raw())) lastFinishHolder[0] = d.raw();
                            else doneSeen = true;
                        }
                    }
                }
                eventLines.clear();
            }
            boolean cancelled = isCancelled.get() || Thread.currentThread().isInterrupted();
            finished.set(true);
            monitor.interrupt();
            return buildStreamMessage(textAccum, toolMap, lastFinishHolder[0], cancelled);
        } catch (IOException ioe) {
            finished.set(true);
            monitor.interrupt();
            boolean cancelled = isCancelled.get() || Thread.currentThread().isInterrupted();
            if (cancelled) {
                return buildStreamMessage(textAccum, toolMap, lastFinishHolder[0], true);
            }
            throw ioe;
        } finally {
            finished.set(true);
            monitor.interrupt();
            try {
                is.close();
            } catch (IOException ignore) {
            }
        }
    }

    /** 流式累积的工具调用分片 */
    private static class ToolAccum {
        String id;
        String name;
        StringBuilder args = new StringBuilder();
    }

    /**
     * 根据累积的文本与工具调用拼装完整 Message，stopReason 对齐同步实现的归一化。
     */
    private Message buildStreamMessage(StringBuilder textAccum, Map<Integer, ToolAccum> toolMap, String lastFinishReason, boolean cancelled) {
        List<Message.Content> contents = new ArrayList<>();
        String text = textAccum.toString();
        if (!text.isEmpty()) {
            contents.add(Message.Content.text(text));
        }
        // 工具调用按 index 有序拼装
        for (Map.Entry<Integer, ToolAccum> e : toolMap.entrySet()) {
            ToolAccum acc = e.getValue();
            String argsJson = acc.args.length() > 0 ? acc.args.toString() : "{}";
            Map<String, Object> argsMap = parseArgsJson(argsJson);
            String id = acc.id != null ? acc.id : "call_" + e.getKey();
            String name = acc.name != null ? acc.name : "unknown";
            Message.ToolCall tc = new Message.ToolCall(id, name, argsMap, argsJson);
            contents.add(Message.Content.toolCall(tc));
        }
        if (contents.isEmpty()) {
            contents.add(Message.Content.text(text));
        }
        String stopReason;
        if (cancelled) {
            stopReason = "aborted";
        } else if (!toolMap.isEmpty()) {
            if ("length".equals(lastFinishReason)) stopReason = "length";
            else stopReason = "toolCalls";
        } else {
            if ("length".equals(lastFinishReason)) stopReason = "length";
            else if ("tool_calls".equals(lastFinishReason)) stopReason = "toolCalls";
            else stopReason = "end";
        }
        Message out = new Message();
        out.role = Message.Role.assistant;
        out.content = contents;
        out.stopReason = stopReason;
        return out;
    }

    private Map<String, Object> parseArgsJson(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            JsonNode node = MAPPER.readTree(json);
            Map<String, Object> map = new HashMap<>();
            if (node.isObject()) {
                node.fields().forEachRemaining(entry -> {
                    JsonNode v = entry.getValue();
                    if (v.isTextual()) map.put(entry.getKey(), v.asText());
                    else if (v.isNumber()) map.put(entry.getKey(), v.numberValue());
                    else if (v.isBoolean()) map.put(entry.getKey(), v.asBoolean());
                    else map.put(entry.getKey(), v.toString());
                });
            }
            return map;
        } catch (Exception e) {
            log.debug("工具参数 JSON 解析失败（可能为中断导致的截断）: {}", json.length() > 200 ? json.substring(0, 200) + "..." : json);
            return Map.of();
        }
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
     * 是否走 opencode 网关：对齐 pi provider-attribution.ts 的守卫
     *（provider 为 opencode 系，或 baseUrl host 为 opencode.ai）。
     */
    static boolean isOpencodeTarget(Model model) {
        if (model.provider() != null && model.provider().startsWith("opencode")) return true;
        String base = model.baseUrl();
        return base != null && base.contains("opencode.ai");
    }

    /**
     * 默认会话 ID：OPENCODE_SESSION_ID 显式覆盖，否则每 client 实例一个随机 UUID。
     * 同一 client 在 REPL 多轮 / AgentLoop 多 turn 内复用，保证会话内稳定，满足网关路由与 prompt caching 要求。
     */
    static String resolveDefaultSessionId() {
        String env = System.getenv("OPENCODE_SESSION_ID");
        if (env != null && !env.isBlank()) return env.trim();
        return UUID.randomUUID().toString();
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
