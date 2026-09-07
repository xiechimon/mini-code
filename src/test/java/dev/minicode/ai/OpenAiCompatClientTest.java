package dev.minicode.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class OpenAiCompatClientTest {

    @Test
    void buildsPayloadWithSystemAndTools() throws Exception {
        OpenAiCompatClient client = new OpenAiCompatClient("test-key");
        Model model = Model.opencodeGo("kimi-k2.6");
        var mapper = new ObjectMapper();
        Tool tool = new Tool("read", "read file", mapper.createObjectNode().put("type", "object"));
        Context ctx = new Context("you are test", List.of(Message.user("hi")), List.of(tool));
        var payload = client.buildPayload(model, ctx);
        assertEquals("kimi-k2.6", payload.get("model").asText());
        assertFalse(payload.get("stream").asBoolean());
        assertEquals(2, payload.get("messages").size()); // system + user
        assertEquals("system", payload.get("messages").get(0).get("role").asText());
        assertEquals(1, payload.get("tools").size());
    }

    @Test
    void parsesResponseWithToolCalls() throws Exception {
        OpenAiCompatClient client = new OpenAiCompatClient("k");
        String json = """
                {"id":"chatcmpl-1","choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":"","tool_calls":[{"id":"call_1","type":"function","function":{"name":"read","arguments":"{\\"path\\":\\"a.txt\\"}"}}]}}]}
                """;
        Message m = client.parseResponse(json);
        assertEquals("toolCalls", m.stopReason);
        assertEquals(1, m.toolCalls().size());
        assertEquals("read", m.toolCalls().get(0).name);
        assertEquals("a.txt", m.toolCalls().get(0).arguments.get("path"));
    }

    @Test
    void parsesPlainTextResponse() throws Exception {
        OpenAiCompatClient client = new OpenAiCompatClient("k");
        String json = """
                {"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"hello java"}}]}
                """;
        Message m = client.parseResponse(json);
        assertEquals("end", m.stopReason);
        assertEquals("hello java", m.text());
    }

    @Test
    void sendsOpencodeSessionHeadersForOpencodeGo() throws Exception {
        var seen = new ConcurrentHashMap<String, String>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestHeaders().forEach((k, v) -> seen.put(k.toLowerCase(), String.join(",", v)));
            String body = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}]}";
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, b.length);
            exchange.getResponseBody().write(b);
            exchange.close();
        });
        server.start();
        int port = server.getAddress().getPort();
        try {
            Model model = new Model("kimi-k2.6", "opencode-go", "http://127.0.0.1:" + port + "/v1", "openai-completions");
            OpenAiCompatClient client = new OpenAiCompatClient("fake-key");
            Context ctx = new Context("sys", List.of(Message.user("hi")), List.of());
            Message msg = client.chat(model, ctx);
            assertEquals("end", msg.stopReason);
            assertNotNull(seen.get("x-opencode-session"), "opencode 网关要求 x-opencode-session，缺失会 400 MissingSessionID");
            assertFalse(seen.get("x-opencode-session").isBlank());
            assertEquals("mini-code", seen.get("x-opencode-client"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sessionIdIsStableWithinSameClient() throws Exception {
        List<Map<String, String>> calls = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            var m = new ConcurrentHashMap<String, String>();
            exchange.getRequestHeaders().forEach((k, v) -> m.put(k.toLowerCase(), String.join(",", v)));
            calls.add(m);
            String body = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}]}";
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, b.length);
            exchange.getResponseBody().write(b);
            exchange.close();
        });
        server.start();
        int port = server.getAddress().getPort();
        try {
            Model model = new Model("kimi-k2.6", "opencode-go", "http://127.0.0.1:" + port + "/v1", "openai-completions");
            OpenAiCompatClient client = new OpenAiCompatClient("fake-key");
            Context ctx = new Context("sys", List.of(Message.user("hi")), List.of());
            client.chat(model, ctx);
            client.chat(model, ctx);
            assertEquals(2, calls.size());
            assertEquals(calls.get(0).get("x-opencode-session"), calls.get(1).get("x-opencode-session"),
                    "同一会话内 session 必须稳定，否则网关路由与缓存失效");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void respectsExplicitSessionIdOverride() throws Exception {
        var seen = new ConcurrentHashMap<String, String>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestHeaders().forEach((k, v) -> seen.put(k.toLowerCase(), String.join(",", v)));
            String body = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}]}";
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, b.length);
            exchange.getResponseBody().write(b);
            exchange.close();
        });
        server.start();
        int port = server.getAddress().getPort();
        try {
            Model model = new Model("kimi-k2.6", "opencode-go", "http://127.0.0.1:" + port + "/v1", "openai-completions");
            OpenAiCompatClient client = new OpenAiCompatClient("fake-key", "pinned-session-123");
            Context ctx = new Context("sys", List.of(Message.user("hi")), List.of());
            client.chat(model, ctx);
            assertEquals("pinned-session-123", seen.get("x-opencode-session"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void doesNotSendOpencodeHeadersForNonOpencode() throws Exception {
        var seen = new ConcurrentHashMap<String, String>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestHeaders().forEach((k, v) -> seen.put(k.toLowerCase(), String.join(",", v)));
            String body = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}]}";
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, b.length);
            exchange.getResponseBody().write(b);
            exchange.close();
        });
        server.start();
        int port = server.getAddress().getPort();
        try {
            Model model = new Model("deepseek-chat", "deepseek", "http://127.0.0.1:" + port + "/v1", "openai-completions");
            OpenAiCompatClient client = new OpenAiCompatClient("fake-key");
            Context ctx = new Context("sys", List.of(Message.user("hi")), List.of());
            client.chat(model, ctx);
            assertNull(seen.get("x-opencode-session"));
            assertNull(seen.get("x-opencode-client"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void buildErrorHintForMuseSpark500() {
        OpenAiCompatClient client = new OpenAiCompatClient("k");
        Model m = Model.opencodeGo("muse-spark-1.2-contributor");
        String hint = client.buildErrorHint(500, "{\"type\":\"error\",\"error\":{\"message\":\"Internal server error\"}}", m);
        assertTrue(hint.contains("muse-spark-1.2-contributor"));
        assertTrue(hint.contains("kimi-k2.6"));
    }

    @Test
    void retriesOn500AndSucceeds() throws Exception {
        AtomicInteger count = new AtomicInteger(0);
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            int n = count.incrementAndGet();
            if (n == 1) {
                String body = "{\"type\":\"error\",\"error\":{\"message\":\"Internal server error\"}}";
                exchange.sendResponseHeaders(500, body.length());
                exchange.getResponseBody().write(body.getBytes(StandardCharsets.UTF_8));
            } else {
                String body = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"ok after retry\"}}]}";
                exchange.sendResponseHeaders(200, body.length());
                exchange.getResponseBody().write(body.getBytes(StandardCharsets.UTF_8));
            }
            exchange.close();
        });
        server.start();
        int port = server.getAddress().getPort();
        try {
            Model model = new Model("test-model", "opencode-go", "http://127.0.0.1:" + port + "/v1", "openai-completions");
            OpenAiCompatClient client = new OpenAiCompatClient("fake-key");
            Context ctx = new Context("sys", List.of(Message.user("hi")), List.of());
            Message msg = client.chat(model, ctx);
            assertEquals("end", msg.stopReason);
            assertEquals("ok after retry", msg.text());
            assertEquals(2, count.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void returnsErrorAfterRetriesExhausted() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String body = "{\"type\":\"error\",\"error\":{\"message\":\"Internal server error\"}}";
            exchange.sendResponseHeaders(500, body.length());
            exchange.getResponseBody().write(body.getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        server.start();
        int port = server.getAddress().getPort();
        try {
            Model model = Model.opencodeGo("muse-spark-1.2-contributor");
            // 覆盖 baseUrl 指向本地假服务
            Model local = new Model(model.id(), model.provider(), "http://127.0.0.1:" + port + "/v1", model.api());
            OpenAiCompatClient client = new OpenAiCompatClient("fake-key");
            Context ctx = new Context("sys", List.of(Message.user("hi")), List.of());
            Message msg = client.chat(local, ctx);
            assertEquals("error", msg.stopReason);
            assertTrue(msg.text().contains("500"));
            assertTrue(msg.text().contains("muse-spark"));
        } finally {
            server.stop(0);
        }
    }
}
