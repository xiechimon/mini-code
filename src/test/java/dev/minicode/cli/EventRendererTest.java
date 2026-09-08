package dev.minicode.cli;

import dev.minicode.agent.AgentEvent;
import dev.minicode.ai.Message;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 事件渲染器单测：纯函数输出字符串断言，覆盖摘要规则、折叠规则、颜色角色与 60/500 边界及横幅/轮末统计。
 * <p>
 * 融合两票：02 的工具调用摘要（read/write/edit→path、bash→command 截60、其他→紧凑JSON截60）、
 * 工具结果（成功首行+多行折叠「… 共 N 行」、失败全文封顶500）、四色角色（工具名青、参数暗灰、成功绿、失败红、轮首暗灰）
 * + 03 的横幅一行（mini-code · provider/model · workdir）与轮末统计（N 轮 · M 次工具 · X.Xs，耗时注入）。
 * 约束：渲染器不读环境、不碰时钟；耗时与计数由调用方注入。
 * </p>
 */
class EventRendererTest {

    private final Style plain = Style.plain();
    private final Style colored = Style.colored();

    // ANSI 期望（与 EventRenderer 一致，零依赖手写）
    private static final String ANSI_CYAN = "\u001B[36m";
    private static final String ANSI_GRAY = "\u001B[90m";
    private static final String ANSI_GREEN = "\u001B[32m";
    private static final String ANSI_RED = "\u001B[31m";

    private static String strip(String s) {
        return s.replaceAll("\u001B\\[[0-9;]*m", "");
    }

    // —— 轮首提示（暗灰） ——

    @Test
    void turnStartRenders() {
        AgentEvent e = new AgentEvent.TurnStart(1);
        String outPlain = EventRenderer.render(e, plain);
        assertEquals("\n[第 1 轮] 思考中...", outPlain);
        assertFalse(outPlain.contains("\u001B["));

        String outColor = EventRenderer.render(e, colored);
        assertTrue(outColor.contains(ANSI_GRAY), "有色轮首应含暗灰");
        assertTrue(outColor.contains("思考中"));
        assertEquals(outPlain, strip(outColor));
        // 耗时注入不影响
        assertEquals(outPlain, strip(EventRenderer.render(e, colored, Duration.ofSeconds(1))));
        assertEquals(outPlain, strip(EventRenderer.render(e, plain, Duration.ofMillis(123))));
    }

    @Test
    void turnStartMultipleRounds() {
        assertEquals("\n[第 2 轮] 思考中...", EventRenderer.render(new AgentEvent.TurnStart(2), plain));
        assertEquals("\n[第 10 轮] 思考中...", EventRenderer.render(new AgentEvent.TurnStart(10), plain));
        String c2 = EventRenderer.render(new AgentEvent.TurnStart(2), colored);
        assertTrue(c2.contains(ANSI_GRAY));
        assertEquals("\n[第 2 轮] 思考中...", strip(c2));
    }

    // —— 助手文本 ——

    @Test
    void messageEndAssistantText() {
        Message msg = Message.assistant(List.of(Message.Content.text("你好，世界")), "end");
        AgentEvent e = new AgentEvent.MessageEnd(msg);
        assertEquals("你好，世界", EventRenderer.render(e, plain));
        assertEquals("你好，世界", strip(EventRenderer.render(e, colored)));
        assertFalse(EventRenderer.render(e, plain).contains("\u001B["));
    }

    @Test
    void messageEndBlankTextNoOutputWhenNoToolCalls() {
        Message msg = Message.assistant(List.of(Message.Content.text("   ")), "end");
        AgentEvent e = new AgentEvent.MessageEnd(msg);
        assertEquals("", EventRenderer.render(e, plain));
        assertEquals("", strip(EventRenderer.render(e, colored)));
    }

    // —— 工具调用行：摘要规则 ——

    @Test
    void paramSummaryReadWriteEditTakesPath() {
        // read
        Message.ToolCall tcRead = new Message.ToolCall("1", "read", Map.of("path", "a.txt"), "{\"path\":\"a.txt\"}");
        Message msgRead = Message.assistant(List.of(Message.Content.toolCall(tcRead)), "toolCalls");
        String plainRead = EventRenderer.render(new AgentEvent.MessageEnd(msgRead), plain);
        assertEquals("→ read a.txt", plainRead);
        String coloredRead = EventRenderer.render(new AgentEvent.MessageEnd(msgRead), colored);
        assertTrue(coloredRead.contains(ANSI_CYAN), "工具名青");
        assertTrue(coloredRead.contains(ANSI_GRAY), "参数暗灰");
        assertTrue(coloredRead.contains("read"));
        assertTrue(coloredRead.contains("a.txt"));
        assertEquals(plainRead, strip(coloredRead));
        assertFalse(plainRead.contains("\u001B["));

        // write
        Message.ToolCall tcWrite = new Message.ToolCall("2", "write", Map.of("path", "src/out.txt"), "{\"path\":\"src/out.txt\"}");
        Message msgWrite = Message.assistant(List.of(Message.Content.toolCall(tcWrite)), "toolCalls");
        assertEquals("→ write src/out.txt", EventRenderer.render(new AgentEvent.MessageEnd(msgWrite), plain));

        // edit
        Message.ToolCall tcEdit = new Message.ToolCall("3", "edit", Map.of("path", "README.md"), "{\"path\":\"README.md\"}");
        Message msgEdit = Message.assistant(List.of(Message.Content.toolCall(tcEdit)), "toolCalls");
        assertEquals("→ edit README.md", EventRenderer.render(new AgentEvent.MessageEnd(msgEdit), plain));
    }

    @Test
    void paramSummaryBashTakesCommandAndTruncates60() {
        // 短命令不截断
        Message.ToolCall tcShort = new Message.ToolCall("1", "bash", Map.of("command", "ls -l"), "{\"command\":\"ls -l\"}");
        Message msgShort = Message.assistant(List.of(Message.Content.toolCall(tcShort)), "toolCalls");
        assertEquals("→ bash ls -l", EventRenderer.render(new AgentEvent.MessageEnd(msgShort), plain));

        // 恰好 60 不截断
        String exactly60 = "a".repeat(60);
        Message.ToolCall tc60 = new Message.ToolCall("1", "bash", Map.of("command", exactly60), "{\"command\":\"" + exactly60 + "\"}");
        Message msg60 = Message.assistant(List.of(Message.Content.toolCall(tc60)), "toolCalls");
        String plain60 = EventRenderer.render(new AgentEvent.MessageEnd(msg60), plain);
        assertEquals("→ bash " + exactly60, plain60);
        assertFalse(plain60.endsWith("..."), "恰好60不应追加省略");

        // 61 截断为 60+...
        String over60 = "a".repeat(61);
        Message.ToolCall tc61 = new Message.ToolCall("1", "bash", Map.of("command", over60), "{\"command\":\"" + over60 + "\"}");
        Message msg61 = Message.assistant(List.of(Message.Content.toolCall(tc61)), "toolCalls");
        String plain61 = EventRenderer.render(new AgentEvent.MessageEnd(msg61), plain);
        assertEquals("→ bash " + "a".repeat(60) + "...", plain61);

        // 有色同样规则且剥离后一致
        String colored61 = EventRenderer.render(new AgentEvent.MessageEnd(msg61), colored);
        assertTrue(colored61.contains(ANSI_CYAN));
        assertTrue(colored61.contains(ANSI_GRAY));
        assertEquals(plain61, strip(colored61));
    }

    @Test
    void paramSummaryOtherTakesCompactJsonAndTruncates60() {
        // 其他工具短 JSON
        Map<String, Object> args = Map.of("foo", "bar");
        String json = "{\"foo\":\"bar\"}";
        Message.ToolCall tc = new Message.ToolCall("1", "myTool", args, json);
        Message msg = Message.assistant(List.of(Message.Content.toolCall(tc)), "toolCalls");
        String outPlain = EventRenderer.render(new AgentEvent.MessageEnd(msg), plain);
        // 紧凑 JSON 可能为 {"foo":"bar"}，长度 <60，不截断
        assertTrue(outPlain.startsWith("→ myTool "), "应以工具名+摘要前缀");
        assertTrue(outPlain.contains("foo"));
        assertFalse(outPlain.contains("\u001B["));

        String outColored = EventRenderer.render(new AgentEvent.MessageEnd(msg), colored);
        assertTrue(outColored.contains(ANSI_CYAN));
        assertTrue(outColored.contains(ANSI_GRAY));
        assertEquals(outPlain, strip(outColored));

        // 恰好 60 边界：构造 60 字符的紧凑文本
        String payload60 = "b".repeat(60);
        // 直接用 payload60 作为 argumentsJson（已紧凑）
        Message.ToolCall tc60b = new Message.ToolCall("1", "myTool", null, payload60);
        Message msg60 = Message.assistant(List.of(Message.Content.toolCall(tc60b)), "toolCalls");
        String plain60 = EventRenderer.render(new AgentEvent.MessageEnd(msg60), plain);
        assertEquals("→ myTool " + payload60, plain60);
        assertFalse(plain60.endsWith("..."));

        // 61 截断
        String payload61 = "b".repeat(61);
        Message.ToolCall tc61 = new Message.ToolCall("1", "myTool", null, payload61);
        Message msg61 = Message.assistant(List.of(Message.Content.toolCall(tc61)), "toolCalls");
        String plain61 = EventRenderer.render(new AgentEvent.MessageEnd(msg61), plain);
        assertEquals("→ myTool " + "b".repeat(60) + "...", plain61);
        assertEquals(plain61, strip(EventRenderer.render(new AgentEvent.MessageEnd(msg61), colored)));
    }

    @Test
    void messageEndTextAndToolCallsCombined() {
        Message.ToolCall tc1 = new Message.ToolCall("1", "bash", Map.of("command", "ls -l"), "{\"command\":\"ls -l\"}");
        Message.ToolCall tc2 = new Message.ToolCall("2", "read", Map.of("path", "README.md"), "{\"path\":\"README.md\"}");
        Message msg = Message.assistant(List.of(
                Message.Content.text("准备执行"),
                Message.Content.toolCall(tc1),
                Message.Content.toolCall(tc2)
        ), "toolCalls");
        AgentEvent e = new AgentEvent.MessageEnd(msg);
        String outPlain = EventRenderer.render(e, plain);
        assertEquals("准备执行\n→ bash ls -l\n→ read README.md", outPlain);
        String outColored = EventRenderer.render(e, colored);
        assertTrue(outColored.contains(ANSI_CYAN));
        assertTrue(outColored.contains(ANSI_GRAY));
        assertEquals(outPlain, strip(outColored));
    }

    @Test
    void messageEndNonAssistantIgnored() {
        Message msg = Message.user("hello");
        AgentEvent e = new AgentEvent.MessageEnd(msg);
        assertEquals("", EventRenderer.render(e, plain));
        assertEquals("", strip(EventRenderer.render(e, colored)));
    }

    // —— 工具结果：成功/失败与 500 边界 ——

    @Test
    void toolResultSuccessSingleLine() {
        Message.ToolCall tc = new Message.ToolCall("1", "read", Map.of("path", "a.txt"), "{}");
        AgentEvent e = new AgentEvent.ToolResultEvent(tc, "文件内容", false);
        String outPlain = EventRenderer.render(e, plain);
        assertEquals("← read: 文件内容", outPlain);
        assertFalse(outPlain.contains("\u001B["));

        String outColored = EventRenderer.render(e, colored);
        assertTrue(outColored.contains(ANSI_CYAN), "工具名青");
        assertTrue(outColored.contains(ANSI_GREEN), "成功绿");
        assertTrue(outColored.contains("文件内容"));
        assertEquals(outPlain, strip(outColored));
    }

    @Test
    void toolResultSuccessMultilineFolding() {
        Message.ToolCall tc = new Message.ToolCall("1", "read", Map.of(), "{}");
        String multiline = "line1\nline2\nline3";
        AgentEvent e = new AgentEvent.ToolResultEvent(tc, multiline, false);
        String outPlain = EventRenderer.render(e, plain);
        assertEquals("← read: line1 … 共 3 行", outPlain);
        assertFalse(outPlain.contains("\u001B["));

        String outColored = EventRenderer.render(e, colored);
        assertTrue(outColored.contains(ANSI_GREEN), "成功绿");
        assertTrue(outColored.contains(ANSI_GRAY), "折叠计数暗灰");
        assertTrue(outColored.contains(ANSI_CYAN));
        assertEquals(outPlain, strip(outColored));

        // 2 行也折叠
        String twoLines = "a\nb";
        AgentEvent e2 = new AgentEvent.ToolResultEvent(tc, twoLines, false);
        assertEquals("← read: a … 共 2 行", EventRenderer.render(e2, plain));
        // 单行不折叠
        AgentEvent e1 = new AgentEvent.ToolResultEvent(tc, "single", false);
        assertEquals("← read: single", EventRenderer.render(e1, plain));
        // null 视为空
        AgentEvent eNull = new AgentEvent.ToolResultEvent(tc, null, false);
        assertEquals("← read: ", EventRenderer.render(eNull, plain));
    }

    @Test
    void toolResultFailureFullAndCap500() {
        Message.ToolCall tc = new Message.ToolCall("1", "bash", Map.of("command", "rm"), "{}");
        AgentEvent e = new AgentEvent.ToolResultEvent(tc, "error: no such file", true);
        String outPlain = EventRenderer.render(e, plain);
        assertEquals("← bash [失败]: error: no such file", outPlain);
        String outColored = EventRenderer.render(e, colored);
        assertTrue(outColored.contains(ANSI_RED), "失败红");
        assertTrue(outColored.contains(ANSI_CYAN));
        assertEquals(outPlain, strip(outColored));
        assertFalse(outPlain.contains("\u001B["));

        // 恰好 500 不截断
        String exactly500 = "x".repeat(500);
        AgentEvent e500 = new AgentEvent.ToolResultEvent(tc, exactly500, true);
        String plain500 = EventRenderer.render(e500, plain);
        assertEquals("← bash [失败]: " + exactly500, plain500);
        assertFalse(plain500.endsWith("..."));

        // 501 截断为 500+...
        String over500 = "x".repeat(501);
        AgentEvent e501 = new AgentEvent.ToolResultEvent(tc, over500, true);
        String plain501 = EventRenderer.render(e501, plain);
        assertEquals("← bash [失败]: " + "x".repeat(500) + "...", plain501);
        String colored501 = EventRenderer.render(e501, colored);
        assertTrue(colored501.contains(ANSI_RED));
        assertEquals(plain501, strip(colored501));

        // 500 边界去色纯文本、有色剥离一致
        assertFalse(EventRenderer.render(e500, plain).contains("\u001B["));
        assertEquals(plain500, strip(EventRenderer.render(e500, colored)));
    }

    @Test
    void toolResultColorRolesPlainHasNoAnsi() {
        Message.ToolCall tc = new Message.ToolCall("1", "write", Map.of("path", "out.txt"), "{\"path\":\"out.txt\"}");
        AgentEvent succ = new AgentEvent.ToolResultEvent(tc, "ok", false);
        AgentEvent fail = new AgentEvent.ToolResultEvent(tc, "boom", true);
        assertFalse(EventRenderer.render(succ, plain).contains("\u001B["));
        assertFalse(EventRenderer.render(fail, plain).contains("\u001B["));
        // 有色包含对应角色色
        assertTrue(EventRenderer.render(succ, colored).contains(ANSI_GREEN));
        assertTrue(EventRenderer.render(fail, colored).contains(ANSI_RED));
        // 剥离后与去色一致
        assertEquals(EventRenderer.render(succ, plain), strip(EventRenderer.render(succ, colored)));
        assertEquals(EventRenderer.render(fail, plain), strip(EventRenderer.render(fail, colored)));
    }

    // —— 横幅单行（03） ——

    @Test
    void bannerPlainRendersSingleLine() {
        Path workdir = Path.of("/tmp/work");
        String out = EventRenderer.renderBanner("opencode-go", "kimi-k2.6", workdir, plain);
        assertEquals("mini-code · opencode-go/kimi-k2.6 · /tmp/work", out);
        assertFalse(out.contains("\u001B["));
        // 单行，无换行
        assertFalse(out.contains("\n"));
    }

    @Test
    void bannerColoredHasBoldAndDim() {
        Path workdir = Path.of("/home/user/project");
        String plainOut = EventRenderer.renderBanner("deepseek", "deepseek-chat", workdir, plain);
        String coloredOut = EventRenderer.renderBanner("deepseek", "deepseek-chat", workdir, colored);
        // 去色不含 ANSI，有色含 ANSI
        assertFalse(plainOut.contains("\u001B["));
        assertTrue(coloredOut.contains("\u001B["), "有色横幅应包含 ANSI");
        // 剥离 ANSI 后与去色一致
        String stripped = coloredOut.replaceAll("\u001B\\[[0-9;]*m", "");
        assertEquals(plainOut, stripped);
        // 名称粗体（1m），其余暗灰（90m 或 2m）
        assertTrue(coloredOut.contains("\u001B[1m"), "名称应为粗体 1m");
        assertTrue(coloredOut.contains("\u001B[90m") || coloredOut.contains("\u001B[2m"), "其余应为暗灰");
        // 内容完整
        assertTrue(stripped.contains("mini-code"));
        assertTrue(stripped.contains("deepseek/deepseek-chat"));
        assertTrue(stripped.contains("/home/user/project"));
        assertTrue(stripped.contains(" · "));
    }

    @Test
    void bannerColoredPlainStrippedEquality() {
        Path workdir = Path.of("/tmp/a");
        String p = EventRenderer.renderBanner("openai", "gpt-4o-mini", workdir, plain);
        String c = EventRenderer.renderBanner("openai", "gpt-4o-mini", workdir, colored);
        assertEquals(p, c.replaceAll("\u001B\\[[0-9;]*m", ""));
    }

    @Test
    void bannerHandlesNullStyleAndNullFields() {
        // null style 按去色处理
        String out = EventRenderer.renderBanner(null, null, null, null);
        assertFalse(out.contains("\u001B["));
        assertTrue(out.contains("mini-code"));
        assertTrue(out.contains("unknown/unknown"));
    }

    // —— 完成行（轮末统计，03 统计格式） ——

    @Test
    void agentEndRendersWithExplicitStats() {
        // 空消息列表，显式注入轮数/工具次数/耗时
        AgentEvent e = new AgentEvent.AgentEnd(List.of());
        // 显式注入 3 轮 5 次工具 1.5s
        String outPlain = EventRenderer.render(e, plain, Duration.ofMillis(1500), 3, 5);
        assertEquals("\n3 轮 · 5 次工具 · 1.5s", outPlain);
        assertFalse(outPlain.contains("\u001B["));
        String outColored = EventRenderer.render(e, colored, Duration.ofMillis(1500), 3, 5);
        // 去色纯文本，有色剥离后一致；规格「成功/失败色也用于轮末统计的对应部分」：M 次工具绿，其余暗灰
        assertEquals(outPlain, strip(outColored));
        assertTrue(outColored.contains(ANSI_GREEN), "M 次工具段应用成功绿");
        assertTrue(outColored.contains(ANSI_GRAY), "N 轮/耗时段应为暗灰");
        assertFalse(outPlain.contains("\u001B["));
    }

    @Test
    void agentEndRendersWithInferenceWhenCountsNotSupplied() {
        // 推断模式：未显式注入计数时，从 messages 推断
        List<Message> messages = List.of(Message.user("hi"), Message.assistant(List.of(Message.Content.text("ok")), "end"));
        AgentEvent e = new AgentEvent.AgentEnd(messages);
        // 未注入计数，推断为 1 轮（1 个 assistant） 0 次工具
        String out = EventRenderer.render(e, plain, Duration.ofSeconds(2));
        assertEquals("\n1 轮 · 0 次工具 · 2.0s", out);
        // 耗时注入生效
        assertEquals("\n1 轮 · 0 次工具 · 0.5s", EventRenderer.render(e, plain, Duration.ofMillis(500)));
        String coloredOut = EventRenderer.render(e, colored, Duration.ofMillis(500));
        assertEquals("\n1 轮 · 0 次工具 · 0.5s", strip(coloredOut));
        assertTrue(coloredOut.contains(ANSI_GREEN), "推断模式有色也应含成功绿（工具段）");
        assertTrue(coloredOut.contains(ANSI_GRAY), "推断模式有色应含暗灰（轮数/耗时）");
        assertFalse(EventRenderer.render(e, plain, Duration.ofMillis(500)).contains("\u001B["));
    }

    @Test
    void agentEndEmptyMessages() {
        AgentEvent e = new AgentEvent.AgentEnd(List.of());
        assertEquals("\n0 轮 · 0 次工具 · 0.0s", EventRenderer.render(e, plain));
        assertEquals("\n0 轮 · 0 次工具 · 0.0s", EventRenderer.render(e, plain, Duration.ZERO, 0, 0));
        assertEquals("\n0 轮 · 0 次工具 · 0.0s", EventRenderer.render(e, plain, null, 0, 0));
    }

    @Test
    void agentEndElapsedFormatting() {
        AgentEvent e = new AgentEvent.AgentEnd(List.of());
        assertEquals("\n1 轮 · 2 次工具 · 0.0s", EventRenderer.render(e, plain, Duration.ofMillis(0), 1, 2));
        assertEquals("\n1 轮 · 2 次工具 · 0.1s", EventRenderer.render(e, plain, Duration.ofMillis(123), 1, 2));
        assertEquals("\n1 轮 · 2 次工具 · 0.1s", EventRenderer.render(e, plain, Duration.ofMillis(50), 1, 2));
        assertEquals("\n1 轮 · 2 次工具 · 1.5s", EventRenderer.render(e, plain, Duration.ofMillis(1499), 1, 2));
        assertEquals("\n2 轮 · 10 次工具 · 12.3s", EventRenderer.render(e, plain, Duration.ofMillis(12345), 2, 10));
        // null 耗时视为 0.0s
        assertEquals("\n1 轮 · 1 次工具 · 0.0s", EventRenderer.render(e, plain, null, 1, 1));
    }

    @Test
    void agentEndStatsPlainAndColoredStrippedEquality() {
        AgentEvent e = new AgentEvent.AgentEnd(List.of(Message.user("hi")));
        String p = EventRenderer.render(e, plain, Duration.ofMillis(1234), 2, 3);
        String c = EventRenderer.render(e, colored, Duration.ofMillis(1234), 2, 3);
        assertEquals(p, strip(c));
        assertFalse(p.contains("\u001B["));
        assertTrue(c.contains(ANSI_GREEN), "有色统计行 M 次工具应为绿");
        assertTrue(c.contains(ANSI_GRAY), "有色统计行 N 轮/耗时应为暗灰");
    }

    @Test
    void agentEndInferToolCallsFromMessages() {
        // 3 条 toolResult 消息，推断 3 次工具
        List<Message> messages = List.of(
                Message.user("hi"),
                Message.assistant(List.of(Message.Content.text("ok")), "end"),
                Message.toolResult("1", "a", false),
                Message.toolResult("2", "b", false),
                Message.toolResult("3", "c", true)
        );
        AgentEvent e = new AgentEvent.AgentEnd(messages);
        // 不显式注入时，工具次数应从消息列表推断为 3
        String out = EventRenderer.render(e, plain, Duration.ofMillis(800), -1, -1);
        assertEquals("\n1 轮 · 3 次工具 · 0.8s", out);
        // 显式注入覆盖推断
        assertEquals("\n2 轮 · 9 次工具 · 0.8s", EventRenderer.render(e, plain, Duration.ofMillis(800), 2, 9));
    }

    // —— 忽略事件 ——

    @Test
    void ignoredEventsReturnEmpty() {
        assertEquals("", EventRenderer.render(new AgentEvent.AgentStart(), plain));
        assertEquals("", strip(EventRenderer.render(new AgentEvent.AgentStart(), colored)));
        assertEquals("", EventRenderer.render(new AgentEvent.ToolStart(new Message.ToolCall("1", "read", Map.of(), "{}")), plain));
        Message msg = Message.assistant(List.of(Message.Content.text("hi")), "end");
        assertEquals("", EventRenderer.render(new AgentEvent.TurnEnd(msg, List.of()), plain));
    }

    // —— 纯函数特性 ——

    @Test
    void rendererIsPureFunctionDeterministic() {
        AgentEvent e = new AgentEvent.TurnStart(3);
        String a = strip(EventRenderer.render(e, colored));
        String b = strip(EventRenderer.render(e, colored));
        assertEquals(a, b, "纯函数应对相同输入产生相同输出");
        String c = strip(EventRenderer.render(e, plain, Duration.ofSeconds(10)));
        String d = strip(EventRenderer.render(e, plain, Duration.ofMillis(1)));
        assertEquals(c, d);
    }

    @Test
    void rendererDoesNotReadEnv() {
        Style s1 = Style.detect(Map.of("NO_COLOR", "1"), true);
        Style s2 = Style.detect(Map.of(), true);
        // 02：工具摘要仅受 Style 影响
        Message.ToolCall tc = new Message.ToolCall("1", "read", Map.of("path", "a.txt"), "{\"path\":\"a.txt\"}");
        Message msg = Message.assistant(List.of(Message.Content.toolCall(tc)), "toolCalls");
        AgentEvent e = new AgentEvent.MessageEnd(msg);
        assertEquals(strip(EventRenderer.render(e, s1)), strip(EventRenderer.render(e, s2)));
        assertEquals("→ read a.txt", strip(EventRenderer.render(e, s1)));
        assertEquals("→ read a.txt", strip(EventRenderer.render(e, s2)));
        // 03：横幅/轮首仅受 Style 影响，剥离后一致
        AgentEvent turn = new AgentEvent.TurnStart(1);
        assertEquals(strip(EventRenderer.render(turn, s1)), strip(EventRenderer.render(turn, s2)));
        Path workdir = Path.of("/tmp/work");
        String b1 = EventRenderer.renderBanner("opencode-go", "kimi-k2.6", workdir, s1);
        String b2 = EventRenderer.renderBanner("opencode-go", "kimi-k2.6", workdir, s2);
        assertFalse(b1.contains("\u001B["));
        assertEquals(b1, b2.replaceAll("\u001B\\[[0-9;]*m", ""));
    }

    // —— 完整链路：去色纯文本、有色含角色色且剥离后一致 ——

    @Test
    void plainAndColoredSameStrippedForAllCovered() {
        // 工具调用
        Message.ToolCall tc = new Message.ToolCall("1", "bash", Map.of("command", "echo hi"), "{\"command\":\"echo hi\"}");
        Message msg = Message.assistant(List.of(Message.Content.toolCall(tc)), "toolCalls");
        AgentEvent call = new AgentEvent.MessageEnd(msg);
        assertEquals(EventRenderer.render(call, plain), strip(EventRenderer.render(call, colored)));
        assertFalse(EventRenderer.render(call, plain).contains("\u001B["));
        assertTrue(strip(EventRenderer.render(call, colored)).equals(EventRenderer.render(call, plain)));

        // 工具结果成功多行
        AgentEvent succMulti = new AgentEvent.ToolResultEvent(tc, "out\nmore", false);
        assertEquals(EventRenderer.render(succMulti, plain), strip(EventRenderer.render(succMulti, colored)));
        // 失败
        AgentEvent fail = new AgentEvent.ToolResultEvent(tc, "err", true);
        assertEquals(EventRenderer.render(fail, plain), strip(EventRenderer.render(fail, colored)));
    }

    @Test
    void elapsedIsInjectedNotFromClock() {
        AgentEvent e = new AgentEvent.AgentEnd(List.of());
        // 相同输入不同 elapsed 应产生不同输出，证明耗时来自注入而非时钟
        String a = EventRenderer.render(e, plain, Duration.ofMillis(100), 1, 1);
        String b = EventRenderer.render(e, plain, Duration.ofMillis(900), 1, 1);
        assertNotEquals(a, b);
        assertEquals("\n1 轮 · 1 次工具 · 0.1s", a);
        assertEquals("\n1 轮 · 1 次工具 · 0.9s", b);
        // 多次相同注入结果确定
        assertEquals(a, EventRenderer.render(e, plain, Duration.ofMillis(100), 1, 1));
    }

    // —— 规格缺口：轮末统计着色（成功绿用于 M 次工具，其余暗灰；去色纯文本） ——

    @Test
    void agentEndStatsColoredRoles() {
        AgentEvent e = new AgentEvent.AgentEnd(List.of());
        String plainOut = EventRenderer.render(e, plain, Duration.ofMillis(1500), 3, 5);
        assertEquals("\n3 轮 · 5 次工具 · 1.5s", plainOut);
        assertFalse(plainOut.contains("\u001B["), "去色模式纯文本");

        String coloredOut = EventRenderer.render(e, colored, Duration.ofMillis(1500), 3, 5);
        // 剥离后与去色一致
        assertEquals(plainOut, strip(coloredOut));
        // 角色色：M 次工具段绿，其余暗灰
        assertTrue(coloredOut.contains(ANSI_GREEN), "M 次工具段应用成功绿");
        assertTrue(coloredOut.contains(ANSI_GRAY), "N 轮/耗时段应为暗灰");
        // 精细：绿应包裹 "5 次工具"，灰应包裹 "3 轮" 与 "1.5s"
        assertTrue(coloredOut.contains(ANSI_GREEN + "5 次工具" + "\u001B[0m"), "绿应精确包裹工具段");
        assertTrue(coloredOut.contains(ANSI_GRAY + "3 轮" + "\u001B[0m"), "暗灰应包裹轮数段");
        assertTrue(coloredOut.contains(ANSI_GRAY + "1.5s" + "\u001B[0m"), "暗灰应包裹耗时段");
        // 通过 TurnStats 同语义
        String viaRecord = EventRenderer.render(e, colored, TurnStats.of(Duration.ofMillis(1500), 3, 5));
        assertEquals(coloredOut, viaRecord);
        String viaInferred = EventRenderer.render(e, plain, TurnStats.inferred(Duration.ofSeconds(2)));
        // inferred 空消息时推断 0 轮 0 工具
        assertEquals("\n0 轮 · 0 次工具 · 2.0s", viaInferred);
    }

    @Test
    void agentEndStatsColoredViaTurnStatsInferredHasSameColors() {
        List<Message> messages = List.of(Message.user("hi"), Message.assistant(List.of(Message.Content.text("ok")), "end"));
        AgentEvent e = new AgentEvent.AgentEnd(messages);
        String coloredInferred = EventRenderer.render(e, colored, TurnStats.inferred(Duration.ofMillis(500)));
        assertEquals("\n1 轮 · 0 次工具 · 0.5s", strip(coloredInferred));
        assertTrue(coloredInferred.contains(ANSI_GREEN));
        assertTrue(coloredInferred.contains(ANSI_GRAY));
    }

    // —— Data Clumps 收敛：TurnStats 小 record ——

    @Test
    void turnStatsRecordReplacesFourArgs() {
        AgentEvent e = new AgentEvent.AgentEnd(List.of());
        // 旧四参重载与新 TurnStats 重载应一致
        String viaFour = EventRenderer.render(e, colored, Duration.ofMillis(1234), 2, 3);
        String viaRecord = EventRenderer.render(e, colored, TurnStats.of(Duration.ofMillis(1234), 2, 3));
        assertEquals(viaFour, viaRecord);
        assertEquals(strip(viaFour), strip(viaRecord));
        // 纯文本一致
        String plainFour = EventRenderer.render(e, plain, Duration.ofMillis(1234), 2, 3);
        String plainRecord = EventRenderer.render(e, plain, TurnStats.of(Duration.ofMillis(1234), 2, 3));
        assertEquals(plainFour, plainRecord);
    }

    @Test
    void turnStatsFactoriesEncapsulateSentinel() {
        TurnStats explicit = TurnStats.of(Duration.ofSeconds(1), 2, 3);
        assertFalse(explicit.inferTurns());
        assertFalse(explicit.inferTools());
        assertEquals(2, explicit.turns());
        assertEquals(3, explicit.toolCalls());

        TurnStats inferred = TurnStats.inferred(Duration.ofSeconds(1));
        assertTrue(inferred.inferTurns());
        assertTrue(inferred.inferTools());
        // elapsed 归一化
        assertEquals("1.0s", EventRenderer.formatElapsed(inferred.elapsedOrZero()));
        assertEquals("0.0s", EventRenderer.formatElapsed(TurnStats.inferred(null).elapsedOrZero()));
    }

    @Test
    void turnStatsNullHandledAsInferred() {
        AgentEvent e = new AgentEvent.AgentEnd(List.of());
        String viaNull = EventRenderer.render(e, plain, (TurnStats) null);
        String viaInferred = EventRenderer.render(e, plain, TurnStats.inferred(null));
        assertEquals(viaInferred, viaNull);
    }
}
