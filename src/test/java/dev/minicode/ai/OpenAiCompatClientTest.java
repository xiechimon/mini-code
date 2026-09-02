package dev.minicode.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OpenAiCompatClientTest {

    @Test
    void buildsPayloadWithSystemAndTools() throws Exception {
        OpenAiCompatClient client = new OpenAiCompatClient("test-key");
        Model model = Model.opencodeGo("kimi-k2.6");
        var mapper = new ObjectMapper();
        Tool tool = new Tool("read", "read file", mapper.createObjectNode().put("type","object"));
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
}
