package dev.minicode.session;

import dev.minicode.ai.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionManagerTest {

    @TempDir Path tmp;

    @Test
    void roundTripMessages() throws IOException {
        Path base = tmp.resolve("home");
        SessionManager m = SessionManager.create(base, "/project/x");
        m.appendMessage(Message.user("你好"));

        Message.ToolCall tc = new Message.ToolCall("call_1", "read",
                Map.of("path", "a.txt"), "{\"path\":\"a.txt\"}");
        Message ass = new Message(Message.Role.assistant, List.of(Message.Content.toolCall(tc)));
        ass.stopReason = "toolCalls";
        m.appendMessage(ass);
        m.appendMessage(Message.toolResult("call_1", "读到了", false));
        Path file = m.filePath();
        m.close();

        assertTrue(Files.exists(file));
        assertTrue(file.getParent().toString().contains("--project-x--"), "应按 cwd 分桶: " + file.getParent());

        List<SessionEntry> entries = SessionStore.load(file);
        assertEquals(4, entries.size(), "头 + 3 条消息");
        assertEquals("session", entries.get(0).type);
        SessionEntry header = entries.get(0);
        assertNotNull(header.id);
        assertTrue(header.id.contains("-"), "会话 id 应为独立 uuid");

        Message user = entries.get(1).message;
        assertEquals(Message.Role.user, user.role);
        assertEquals("你好", user.text());

        Message a = entries.get(2).message;
        assertEquals(Message.Role.assistant, a.role);
        assertEquals("toolCalls", a.stopReason);
        assertEquals(1, a.toolCalls().size());
        assertEquals("read", a.toolCalls().get(0).name);
        assertEquals("a.txt", a.toolCalls().get(0).arguments.get("path"));

        Message tr = entries.get(3).message;
        assertEquals(Message.Role.toolResult, tr.role);
        assertTrue(tr.text().contains("读到了"));

        // parentId 建链：头→user→assistant→toolResult
        assertEquals(header.id, entries.get(1).parentId);
        assertEquals(entries.get(1).id, entries.get(2).parentId);
        assertEquals(entries.get(2).id, entries.get(3).parentId);
    }

    @Test
    void bucketNameEncodesCwd() {
        assertEquals("--Users-x-Code-pi--", SessionManager.bucketName("/Users/x/Code/pi"));
        assertEquals("--tmp-a--", SessionManager.bucketName("/tmp/a"));
        assertEquals("--C--new--", SessionManager.bucketName("C:\\new")); // :与\相邻 → 双 -
    }

    @Test
    void loadSkipsMalformedLines() throws IOException {
        Path file = tmp.resolve("s.jsonl");
        Files.writeString(file,
                "{\"type\":\"session\",\"id\":\"s1\",\"version\":1,\"cwd\":\"/x\"}\n"
                        + "{\"type\":\"message\",\"id\":\"m1\",\"parentId\":\"s1\",\"message\":{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]}}\n"
                        + "{ this is not valid json }\n"
                        + "{\"type\":\"message\",\"id\":\"m2\",\"parentId\":\"m1\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"yo\"}],\"stopReason\":\"end\"}}\n");
        List<SessionEntry> entries = SessionStore.load(file);
        assertEquals(3, entries.size());
        assertEquals("session", entries.get(0).type);
        assertEquals("message", entries.get(1).type);
        assertEquals("m2", entries.get(2).id);
    }

    @Test
    void loadRejectsWhenFirstValidLineNotHeader() throws IOException {
        Path file = tmp.resolve("bad.jsonl");
        Files.writeString(file,
                "{\"type\":\"message\",\"id\":\"m1\",\"parentId\":null,\"message\":{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]}}\n");
        assertThrows(IOException.class, () -> SessionStore.load(file));
    }

    @Test
    void sessionHistoryPersistsOnAddAllAndClearKeepsFile() throws IOException {
        Path base = tmp.resolve("home2");
        SessionManager m = SessionManager.create(base, "/p");
        SessionHistory history = new SessionHistory(m);
        history.addAll(List.of(Message.user("a"), Message.user("b")));
        assertEquals(2, history.size());

        history.clear(); // 内存清空只裁上下文窗，不做会话文件（append-only）
        assertEquals(0, history.size());

        m.close();
        List<SessionEntry> entries = SessionStore.load(m.filePath());
        assertEquals(3, entries.size(), "会话文件应保留头 + 2 条消息");
    }
}
