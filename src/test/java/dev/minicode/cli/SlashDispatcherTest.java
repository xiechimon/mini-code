package dev.minicode.cli;

import dev.minicode.ai.LlmClient;
import dev.minicode.ai.Message;
import dev.minicode.ai.Model;
import dev.minicode.session.CompactionConfig;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 斜杠命令分发表与只读命令单测（.scratch/slash-commands/issues/01）。
 * 测试缝：{@link SlashDispatcher#dispatch} + 命令作为 {@link ReplContext} 上的纯操作，
 * 输出经注入的 PrintStream 断言，全程无终端。fake llm 先例见 AgentLoopTest。
 */
class SlashDispatcherTest {

    @TempDir
    Path tmp;

    // ===== 测试夹具 =====

    private static final class Fixture {
        final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        ReplContext ctx;
        SessionManager session;

        String out() {
            return buf.toString(StandardCharsets.UTF_8);
        }
    }

    /** fake llm：压缩摘要固定返回，对齐 AgentLoopTest 的 LlmClient.fake 先例。 */
    private static LlmClient fakeLlm() {
        return LlmClient.fake((model, c) -> Message.assistant(
                List.of(Message.Content.text("## Goal\n- 测试摘要")), "end"));
    }

    /** 标准夹具：真实会话（@TempDir）+ 内存 history + 内置命令表。keepRecentTokens=5 便于触发真实压缩。 */
    private Fixture newFixture(boolean withSession) throws Exception {
        Fixture f = new Fixture();
        PrintStream out = new PrintStream(f.buf, true, StandardCharsets.UTF_8);
        Model model = Model.of("test-provider", "m1", "http://localhost");
        if (withSession) {
            f.session = SessionManager.create(tmp, tmp.toString());
        }
        List<Message> history = f.session != null ? new SessionHistory(f.session) : new ArrayList<>();
        ContextCompactor compactor = f.session != null
                ? new ContextCompactor(f.session, new CompactionConfig.Resolved(200_000, 16_384, 5))
                : null;
        f.ctx = new ReplContext(tmp, fakeLlm(), List.of(), "sp", null, 20, tmp, out, Style.PLAIN,
                null, model, history, f.session, compactor, new SlashDispatcher(SlashCommands.builtins()));
        return f;
    }

    // ===== 分发表 =====

    @Test
    void nonSlashLineIsNotACommand() throws Exception {
        Fixture f = newFixture(false);
        assertEquals(SlashDispatcher.Result.NOT_A_COMMAND, f.ctx.dispatcher().dispatch("帮我改个文件", f.ctx));
        assertEquals(SlashDispatcher.Result.NOT_A_COMMAND, f.ctx.dispatcher().dispatch("/", f.ctx));
        assertEquals("", f.out());
    }

    @Test
    void unknownSlashFallsThrough() throws Exception {
        Fixture f = newFixture(false);
        // pi fallthrough 终点语义：未注册的 /xxx 原样发给 LLM，调度器不拦截
        assertEquals(SlashDispatcher.Result.NOT_A_COMMAND, f.ctx.dispatcher().dispatch("/nosuch", f.ctx));
    }

    @Test
    void helpListsAllCommands() throws Exception {
        Fixture f = newFixture(false);
        assertEquals(SlashDispatcher.Result.HANDLED, f.ctx.dispatcher().dispatch("/help", f.ctx));
        String out = f.out();
        assertTrue(out.contains("/help"), out);
        assertTrue(out.contains("/session"), out);
        assertTrue(out.contains("/compact"), out);
        assertTrue(out.contains("/exit"), out);
        assertTrue(out.contains("/quit"), out);
    }

    @Test
    void nameMatchingIsCaseInsensitive() throws Exception {
        Fixture f = newFixture(false);
        assertEquals(SlashDispatcher.Result.HANDLED, f.ctx.dispatcher().dispatch("/HELP", f.ctx));
        assertTrue(f.out().contains("/session"));
    }

    @Test
    void argsAreRemainderAfterFirstWhitespace() throws Exception {
        AtomicReference<String> captured = new AtomicReference<>();
        LinkedHashMap<String, SlashCommands.Entry> table = new LinkedHashMap<>();
        table.put("echo", new SlashCommands.Entry("t", false, (args, ctx) -> captured.set(args)));
        Fixture f = newFixture(false);
        SlashDispatcher d = new SlashDispatcher(table);
        d.dispatch("/echo  a b  c ", f.ctx);
        assertEquals("a b  c", captured.get());
        d.dispatch("/echo", f.ctx);
        assertEquals("", captured.get());
    }

    // ===== /session =====

    @Test
    void sessionShowsFileIdCountAndTokens() throws Exception {
        Fixture f = newFixture(true);
        f.ctx.history().add(Message.user("你好"));
        f.ctx.history().add(Message.assistant(List.of(Message.Content.text("你好！")), "end"));
        assertEquals(SlashDispatcher.Result.HANDLED, f.ctx.dispatcher().dispatch("/session", f.ctx));
        String out = f.out();
        assertTrue(out.contains(f.session.sessionId()), out);
        assertTrue(out.contains(f.session.filePath().toString()), out);
        assertTrue(out.contains("2（内存）"), out);
        assertTrue(out.contains("阈值"), out);
        f.session.close();
    }

    @Test
    void sessionDegradedModeReportsUnavailable() throws Exception {
        Fixture f = newFixture(false);
        assertEquals(SlashDispatcher.Result.HANDLED, f.ctx.dispatcher().dispatch("/session", f.ctx));
        assertTrue(f.out().contains("不可用"), f.out());
    }

    // ===== /compact =====

    @Test
    void compactShrinksHistoryAndWritesEntry() throws Exception {
        Fixture f = newFixture(true);
        // 6 条各 ~100 字符的消息：keepRecentTokens=5 → 保留段 1 条，压缩段 5 条
        for (int i = 0; i < 6; i++) {
            f.ctx.history().add(Message.user("第 " + i + " 轮 " + "x".repeat(100)));
        }
        assertEquals(6, f.ctx.history().size());
        assertEquals(SlashDispatcher.Result.HANDLED, f.ctx.dispatcher().dispatch("/compact", f.ctx));
        assertEquals(1, f.ctx.history().size(), "压缩后内存 history 只剩保留段");
        String out = f.out();
        assertTrue(out.contains("已压缩"), out);
        String jsonl = Files.readString(f.session.filePath());
        assertTrue(jsonl.contains("\"compaction\""), "会话 JSONL 应出现 compaction 条目");
        f.session.close();
    }

    @Test
    void compactEmptyHistoryIsNoop() throws Exception {
        Fixture f = newFixture(true);
        long linesBefore = Files.lines(f.session.filePath()).count();
        assertEquals(SlashDispatcher.Result.HANDLED, f.ctx.dispatcher().dispatch("/compact", f.ctx));
        assertTrue(f.out().contains("无需压缩"), f.out());
        assertEquals(linesBefore, Files.lines(f.session.filePath()).count(), "空 history 不写 compaction 条目");
        f.session.close();
    }

    @Test
    void compactDegradedModeReportsUnavailable() throws Exception {
        Fixture f = newFixture(false);
        f.ctx.history().add(Message.user("x"));
        assertEquals(SlashDispatcher.Result.HANDLED, f.ctx.dispatcher().dispatch("/compact", f.ctx));
        assertTrue(f.out().contains("不可用"), f.out());
    }

    // ===== Tab 补全候选 / 命令提示 Completer =====

    @Test
    void completionNamesCoverRegistryAndExit() {
        List<String> names = SlashCommands.completionNames(null);
        assertTrue(names.contains("/help"));
        assertTrue(names.contains("/session"));
        assertTrue(names.contains("/compact"));
        assertTrue(names.contains("/exit"));
        assertTrue(names.contains("/quit"));
    }

    @Test
    void completionNamesFollowDispatcherRegistry() {
        Map<String, SlashCommands.Entry> table = Map.of("zzz", new SlashCommands.Entry("t", false, (a, c) -> {
        }));
        List<String> names = SlashCommands.completionNames(new SlashDispatcher(table));
        assertTrue(names.contains("/zzz"), "补全候选应与调度器注册表一致");
        assertFalse(names.contains("/help"));
    }

    /** takesArg 标记：命令面板 Enter 分流依据——带参命令仅 /model /export（见 .scratch/command-panel/spec.md）。 */
    @Test
    void takesArgOnlyModelAndExport() {
        Map<String, SlashCommands.Entry> table = SlashCommands.builtins();
        table.forEach((name, e) -> {
            boolean expect = name.equals("model") || name.equals("export");
            assertEquals(expect, e.takesArg(), "/" + name + " takesArg 标记不符");
        });
    }

    /**
     * 命令提示 Completer（输 / 即出列表的数据源）：候选带描述列，与 /help 同源；
     * 仅首 token 出候选；/exit /quit 虽不入注册表但在提示中可见。
     */
    @Test
    void commandCompleterEmitsDescriptionsAndExit() {
        var completer = SlashCommands.commandCompleter(null);
        List<org.jline.reader.Candidate> candidates = new ArrayList<>();
        var parsed = new org.jline.reader.impl.DefaultParser().parse("/c", 2);
        // wordIndex==0 的首 token 才有候选
        completer.complete(null, parsed, candidates);
        assertFalse(candidates.isEmpty());
        var byValue = new java.util.HashMap<String, org.jline.reader.Candidate>();
        for (var c : candidates) byValue.put(c.value(), c);
        assertTrue(byValue.containsKey("/compact"), byValue.keySet().toString());
        assertTrue(byValue.containsKey("/exit"), byValue.keySet().toString());
        assertTrue(byValue.containsKey("/quit"), byValue.keySet().toString());
        assertNotNull(byValue.get("/compact").descr(), "候选应带描述列（与 /help 同源）");
    }

    @Test
    void commandCompleterIgnoresNonFirstToken() {
        var completer = SlashCommands.commandCompleter(null);
        List<org.jline.reader.Candidate> candidates = new ArrayList<>();
        var parsed = new org.jline.reader.impl.DefaultParser().parse("/model m2", 8);
        // wordIndex==1（参数位）不出候选
        completer.complete(null, parsed, candidates);
        assertTrue(candidates.isEmpty());
    }
}
