package dev.minicode.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
