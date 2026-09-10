package dev.minicode.session;

import dev.minicode.ai.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompactionTest {

    @TempDir Path tmp;

    @Test
    void compactionEntryRoundTripAndProjectedSummary() throws IOException {
        Path base = tmp.resolve("home");
        SessionManager m = SessionManager.create(base, "/p");

        // 灌入 5 条 message
        for (int i = 0; i < 5; i++) {
            m.appendMessage(Message.user("ask-" + i));
        }
        assertEquals(5, SessionStore.load(m.filePath()).size() - 1);

        // 追加 compaction 条目（直接走 SessionManager API）
        m.appendCompaction("## Goal\n- summary text\n", null, 1234);

        // 重建视图：见到 <summary> 系统消息
        List<Message> ctx = SessionContext.loadFromEntries(SessionStore.load(m.filePath()));
        assertFalse(ctx.isEmpty());
        assertEquals(Message.Role.system, ctx.get(0).role);
        assertTrue(ctx.get(0).text().contains("<summary>"));
        assertTrue(ctx.get(0).text().contains("summary text"));

        m.close();
    }

    @Test
    void shouldCompactTriggersOnLargeHistory() throws IOException {
        Path base = tmp.resolve("home2");
        SessionManager m = SessionManager.create(base, "/p");
        // 灌很多以超阈值（默认窗口 128k）
        List<Message> big = new ArrayList<>();
        // 每条 = 200 chars ≈ 50 tokens，总 40 条 = 2000 tokens，远小于窗口 默认阈值
        // 直接用超大字符串触发（不留固定假设）
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 200_000; i++) huge.append('x'); // ~50k tokens/条 × 5 = 250k tokens > 窗口
        for (int i = 0; i < 5; i++) big.add(Message.user(huge.toString()));
        ContextCompactor cc = new ContextCompactor(m);
        assertTrue(cc.shouldCompact(big));
        m.close();
    }

    @Test
    void compactWritesCompactionEntryAndReturnsKeepRecent() throws Exception {
        Path base = tmp.resolve("home3");
        SessionManager m = SessionManager.create(base, "/p");
        // keepRecent=8 tokens 很紧，配合 100 chars/条（约 25 tokens/条）灌 5 条 → 触发压缩、保留不到 5 条
        CompactionConfig.Resolved cfg = new CompactionConfig.Resolved(200_000L, 16_384, 8);
        ContextCompactor cc = new ContextCompactor(m, cfg);

        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 100; i++) big.append('x'); // 100 chars ≈ 25 tokens
        List<Message> history = new ArrayList<>();
        for (int i = 0; i < 5; i++) history.add(Message.user(big.toString()));

        List<Message> kept = cc.compact(history, null, null, null);
        assertTrue(kept.size() < history.size(), "压缩应减少历史");
        assertTrue(kept.size() >= 1);

        // 文件内应有一份 compaction 条目
        int compactions = 0;
        for (SessionEntry e : SessionStore.load(m.filePath())) {
            if (SessionEntry.TYPE_COMPACTION.equals(e.type)) compactions++;
        }
        assertEquals(1, compactions);

        m.close();
    }

    @Test
    void pickKeepRecentAccumulatesFromTail() {
        List<Message> history = new ArrayList<>();
        for (int i = 0; i < 6; i++) history.add(Message.user("aaaaa")); // 5 chars ≈ 2 tokens/条
        List<Message> kept = ContextCompactor.pickKeepRecent(history, 5);
        // 5 tokens ≈ 2-3 条（5 chars ≈ 2 tokens）
        assertTrue(kept.size() >= 2 && kept.size() <= 4);
        // 保留段就是尾部
        assertEquals("aaaaa", kept.get(kept.size() - 1).text());
    }

    @Test
    void fallbackSummaryShape() {
        List<Message> h = List.of(Message.user("alpha"), Message.user("beta"));
        String s = ContextCompactor.buildFallbackSummary(h);
        assertTrue(s.contains("## Goal"));
        assertTrue(s.contains("## Progress"));
        assertTrue(s.contains("## Key Decisions"));
        assertTrue(s.contains("## Next"));
    }

    @Test
    void emptyHistoryNoOp() throws IOException {
        Path base = tmp.resolve("home4");
        SessionManager m = SessionManager.create(base, "/p");
        ContextCompactor cc = new ContextCompactor(m);
        List<Message> kept = cc.compact(new ArrayList<>(), null, null, null);
        assertTrue(kept.isEmpty());
        m.close();
    }
}
