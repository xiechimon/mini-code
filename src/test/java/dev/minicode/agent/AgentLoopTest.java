package dev.minicode.agent;

import dev.minicode.ai.LlmClient;
import dev.minicode.ai.Message;
import dev.minicode.ai.Model;
import dev.minicode.tools.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentLoopTest {

    @Test
    void singleTurnNoTools(@TempDir Path tmp) throws Exception {
        LlmClient fake = (model, ctx) -> {
            Message m = new Message();
            m.role = Message.Role.assistant;
            m.content = List.of(Message.Content.text("done"));
            m.stopReason = "end";
            return m;
        };
        Model model = Model.opencodeGo("kimi-k2.6");
        AgentLoop loop = new AgentLoop(fake, model, "you are mini", List.of(), 5);
        List<Message> out = loop.run(List.of(Message.user("hi")), null);
        assertEquals(2, out.size()); // user + assistant
        assertEquals("done", out.get(1).text());
    }

    @Test
    void toolCallSequenceEditsFile(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("hello.txt");
        Files.writeString(f, "hello world");

        List<ToolDefinition> tools = List.of(new ReadTool(tmp), new EditTool(tmp));

        AtomicInteger call = new AtomicInteger(0);
        LlmClient fake = (model, ctx) -> {
            int c = call.getAndIncrement();
            if (c == 0) {
                // first turn: read file
                Message.ToolCall tc = new Message.ToolCall("tc1", "read", Map.of("path", "hello.txt"), "{\"path\":\"hello.txt\"}");
                Message m = new Message();
                m.role = Message.Role.assistant;
                m.content = List.of(Message.Content.toolCall(tc));
                m.stopReason = "toolCalls";
                return m;
            } else if (c == 1) {
                // second turn: edit file
                Message.ToolCall tc = new Message.ToolCall("tc2", "edit",
                        Map.of("path", "hello.txt", "oldText", "world", "newText", "java"),
                        "{\"path\":\"hello.txt\",\"oldText\":\"world\",\"newText\":\"java\"}");
                Message m = new Message();
                m.role = Message.Role.assistant;
                m.content = List.of(Message.Content.toolCall(tc));
                m.stopReason = "toolCalls";
                return m;
            } else {
                Message m = new Message();
                m.role = Message.Role.assistant;
                m.content = List.of(Message.Content.text("edited!"));
                m.stopReason = "end";
                return m;
            }
        };

        Model model = Model.opencodeGo("kimi-k2.6");
        AgentLoop loop = new AgentLoop(fake, model, "system", tools, 10);
        List<Message> out = loop.run(List.of(Message.user("change world to java")), null);

        // user + assistant(read) + toolResult + assistant(edit) + toolResult + assistant(end) = 6
        assertEquals(6, out.size());
        assertEquals("hello java", Files.readString(f));
        assertTrue(out.get(out.size() - 1).text().contains("edited"));
    }

    @Test
    void handlesLengthTruncation(@TempDir Path tmp) throws Exception {
        AtomicInteger call = new AtomicInteger(0);
        LlmClient fake = (model, ctx) -> {
            if (call.getAndIncrement() == 0) {
                Message.ToolCall tc = new Message.ToolCall("tc1", "read", Map.of("path", "a"), "{}");
                Message m = new Message();
                m.role = Message.Role.assistant;
                m.content = List.of(Message.Content.toolCall(tc));
                m.stopReason = "length"; // truncated
                return m;
            } else {
                Message m = new Message();
                m.role = Message.Role.assistant;
                m.content = List.of(Message.Content.text("recovered"));
                m.stopReason = "end";
                return m;
            }
        };
        AgentLoop loop = new AgentLoop(fake, Model.opencodeGo("k"), "", List.of(new ReadTool(tmp)), 5);
        List<Message> out = loop.run(List.of(Message.user("hi")), null);
        // should have: user, assistant(length), toolResult(error), assistant(recovered)
        assertEquals(4, out.size());
        assertTrue(out.get(2).text().contains("截断") || out.get(2).text().contains("truncated") || out.get(2).content.get(0).isError);
    }

    @Test
    void tracerBulletWriteThenBash(@TempDir Path tmp) throws Exception {
        List<ToolDefinition> tools = List.of(new WriteTool(tmp), new BashTool(tmp));
        AtomicInteger call = new AtomicInteger(0);
        LlmClient fake = (model, ctx) -> {
            int c = call.getAndIncrement();
            if (c == 0) {
                Message.ToolCall tc = new Message.ToolCall("c1", "write", Map.of("path", "out.txt", "content", "hello"), "{\"path\":\"out.txt\",\"content\":\"hello\"}");
                Message m = new Message();
                m.role = Message.Role.assistant;
                m.content = List.of(Message.Content.toolCall(tc));
                m.stopReason = "toolCalls";
                return m;
            } else if (c == 1) {
                Message.ToolCall tc = new Message.ToolCall("c2", "bash", Map.of("command", "cat out.txt"), "{\"command\":\"cat out.txt\"}");
                Message m = new Message();
                m.role = Message.Role.assistant;
                m.content = List.of(Message.Content.toolCall(tc));
                m.stopReason = "toolCalls";
                return m;
            } else {
                Message m = new Message();
                m.role = Message.Role.assistant;
                m.content = List.of(Message.Content.text("verified"));
                m.stopReason = "end";
                return m;
            }
        };
        AgentLoop loop = new AgentLoop(fake, Model.opencodeGo("k"), "", tools, 10);
        List<Message> out = loop.run(List.of(Message.user("write and cat")), null);
        assertEquals("hello", Files.readString(tmp.resolve("out.txt")).trim());
        // check bash output was captured in tool result
        assertTrue(out.stream().anyMatch(m -> m.role == Message.Role.toolResult && m.text().contains("hello")));
    }
}
