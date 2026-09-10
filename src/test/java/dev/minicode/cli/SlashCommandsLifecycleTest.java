package dev.minicode.cli;

import dev.minicode.agent.AgentLoop;
import dev.minicode.ai.LlmClient;
import dev.minicode.ai.Message;
import dev.minicode.ai.Model;
import dev.minicode.session.ContextCompactor;
import dev.minicode.session.SessionHistory;
import dev.minicode.session.SessionManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 生命周期命令单测（.scratch/slash-commands/issues/02）：/model /new /export。
 * 缝与先例同 {@link SlashDispatcherTest}：命令 = ReplContext 上的纯操作，输出经注入 PrintStream 断言。
 */
class SlashCommandsLifecycleTest {

    @TempDir
    Path tmp;

    private static final class Fixture {
        final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        ReplContext ctx;
        SessionManager session;
        AgentLoop loop;
        Model model = Model.of("test-provider", "m1", "http://localhost");

        String out() {
            return buf.toString(StandardCharsets.UTF_8);
        }
    }

    private static LlmClient fakeLlm() {
        return LlmClient.fake((model, c) -> Message.assistant(
                List.of(Message.Content.text("ok")), "end"));
    }

    /** withSession=false 模拟降级模式（会话初始化失败）。 */
    private Fixture newFixture(boolean withSession) throws Exception {
        Fixture f = new Fixture();
        PrintStream out = new PrintStream(f.buf, true, StandardCharsets.UTF_8);
        LlmClient llm = fakeLlm();
        f.loop = new AgentLoop(llm, f.model, "sp", List.of(), 20);
        if (withSession) {
            f.session = SessionManager.create(tmp, tmp.toString());
        }
        List<Message> history = f.session != null ? new SessionHistory(f.session) : new java.util.ArrayList<>();
        ContextCompactor compactor = f.session != null ? new ContextCompactor(f.session) : null;
        f.ctx = new ReplContext(tmp, llm, List.of(), "sp", null, 20, tmp, out, Style.PLAIN,
                f.loop, f.model, history, f.session, compactor, new SlashDispatcher(SlashCommands.builtins()));
        return f;
    }

    // ===== /model =====

    @Test
    void modelShowsCurrentWhenNoArgs() throws Exception {
        Fixture f = newFixture(true);
        f.ctx.dispatcher().dispatch("/model", f.ctx);
        String out = f.out();
        assertTrue(out.contains("test-provider"), out);
        assertTrue(out.contains("m1"), out);
        assertTrue(out.contains("http://localhost"), out);
        f.session.close();
    }

    @Test
    void modelSwitchReplacesLoopAndModel() throws Exception {
        Fixture f = newFixture(true);
        AgentLoop before = f.ctx.currentLoop();
        f.ctx.dispatcher().dispatch("/model m2", f.ctx);
        assertEquals("m2", f.ctx.model().id());
        assertNotSame(before, f.ctx.currentLoop(), "切换后应换入新 AgentLoop 实例");
        assertEquals("test-provider", f.ctx.model().provider(), "provider 不变");
        assertTrue(f.out().contains("m1 → m2"), f.out());
        f.session.close();
    }

    @Test
    void modelRejectsCrossProviderSyntax() throws Exception {
        Fixture f = newFixture(true);
        f.ctx.dispatcher().dispatch("/model deepseek/deepseek-chat", f.ctx);
        assertEquals("m1", f.ctx.model().id(), "跨 provider 写法应被拒绝且模型不变");
        assertTrue(f.out().contains("同 provider"), f.out());
        f.session.close();
    }

    // ===== /new =====

    @Test
    void newSessionSwapsStateAndKeepsOldFile() throws Exception {
        Fixture f = newFixture(true);
        Path oldFile = f.session.filePath();
        String oldId = f.session.sessionId();
        f.ctx.history().add(Message.user("旧会话消息"));
        f.ctx.dispatcher().dispatch("/new", f.ctx);
        assertTrue(f.ctx.history().isEmpty(), "新会话内存 history 应为空");
        assertNotEquals(oldId, f.ctx.session().sessionId(), "应换入新会话");
        assertTrue(Files.exists(oldFile), "旧会话 JSONL 应留盘");
        assertTrue(Files.exists(f.ctx.session().filePath()), "新会话文件应落盘");
        String newJsonl = Files.readString(f.ctx.session().filePath());
        assertTrue(newJsonl.contains("\"session\""), "新文件应含会话头");
        assertNotNull(f.ctx.compactor(), "压缩器应随新会话重建");
        f.ctx.session().close();
    }

    @Test
    void newSessionInDegradedModeRetries() throws Exception {
        Fixture f = newFixture(false);
        assertNull(f.ctx.session());
        f.ctx.dispatcher().dispatch("/new", f.ctx);
        assertNotNull(f.ctx.session(), "降级模式下 /new 应重试建会话");
        assertTrue(f.out().contains("已开新会话"), f.out());
        f.ctx.session().close();
    }

    // ===== /export =====

    @Test
    void exportCopiesSessionJsonl() throws Exception {
        Fixture f = newFixture(true);
        f.ctx.history().add(Message.user("导我行"));
        Path target = tmp.resolve("out.jsonl");
        f.ctx.dispatcher().dispatch("/export " + target, f.ctx);
        assertTrue(Files.exists(target));
        assertEquals(Files.readString(f.session.filePath()), Files.readString(target), "导出内容应与会话文件一致");
        assertTrue(f.out().contains("已导出"), f.out());
        f.session.close();
    }

    @Test
    void exportDefaultNameInWorkdir() throws Exception {
        Fixture f = newFixture(true);
        f.ctx.dispatcher().dispatch("/export", f.ctx);
        try (var stream = Files.list(tmp)) {
            assertTrue(stream.anyMatch(p -> p.getFileName().toString().startsWith("session-")
                    && p.getFileName().toString().endsWith(".jsonl")), "应在 workdir 生成 session-<ts>.jsonl");
        }
        f.session.close();
    }

    @Test
    void exportRefusesToOverwriteExisting() throws Exception {
        Fixture f = newFixture(true);
        Path target = tmp.resolve("dup.jsonl");
        Files.writeString(target, "已占用");
        f.ctx.dispatcher().dispatch("/export " + target, f.ctx);
        assertEquals("已占用", Files.readString(target), "目标已存在时不静默覆盖");
        assertTrue(f.out().contains("已存在"), f.out());
        f.session.close();
    }

    @Test
    void exportDegradedModeReportsUnavailable() throws Exception {
        Fixture f = newFixture(false);
        f.ctx.dispatcher().dispatch("/export", f.ctx);
        assertTrue(f.out().contains("不可用"), f.out());
    }

    // ===== /help 注册表驱动 =====

    @Test
    void helpAutoIncludesLifecycleCommands() throws Exception {
        Fixture f = newFixture(false);
        f.ctx.dispatcher().dispatch("/help", f.ctx);
        String out = f.out();
        assertTrue(out.contains("/model"), out);
        assertTrue(out.contains("/new"), out);
        assertTrue(out.contains("/export"), out);
    }
}
