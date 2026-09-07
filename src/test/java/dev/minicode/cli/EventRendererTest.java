package dev.minicode.cli;

import dev.minicode.agent.AgentEvent;
import dev.minicode.ai.Message;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 事件渲染器单测：纯函数输出字符串断言，覆盖摘要规则、折叠规则、颜色角色与 60/500 边界。
 * <p>
 * 票 02 实现：工具调用摘要（read/write/edit→path、bash→command 截60、其他→紧凑JSON截60）、
 * 工具结果（成功首行+多行折叠「… 共 N 行」、失败全文封顶500）、四色角色（工具名青、参数暗灰、成功绿、失败红、轮首暗灰）及去色纯文本。
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

    // —— 完成行（03 不动，保持等价） ——

    @Test
    void agentEndRenders() {
        List<Message> messages = List.of(Message.user("hi"), Message.assistant(List.of(Message.Content.text("ok")), "end"));
        AgentEvent e = new AgentEvent.AgentEnd(messages);
        assertEquals("\n[mini-code] 完成（共 2 条消息）", EventRenderer.render(e, plain));
        assertEquals("\n[mini-code] 完成（共 2 条消息）", strip(EventRenderer.render(e, colored)));
        // 耗时注入当前不影响输出（为 03 预留）
        assertEquals("\n[mini-code] 完成（共 2 条消息）", EventRenderer.render(e, plain, Duration.ofSeconds(2)));
        assertEquals("\n[mini-code] 完成（共 2 条消息）", strip(EventRenderer.render(e, colored, Duration.ofMillis(500))));
        // 去色无 ANSI
        assertFalse(EventRenderer.render(e, plain).contains("\u001B["));
    }

    @Test
    void agentEndEmptyMessages() {
        AgentEvent e = new AgentEvent.AgentEnd(List.of());
        assertEquals("\n[mini-code] 完成（共 0 条消息）", EventRenderer.render(e, plain));
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
        Message.ToolCall tc = new Message.ToolCall("1", "read", Map.of("path", "a.txt"), "{\"path\":\"a.txt\"}");
        Message msg = Message.assistant(List.of(Message.Content.toolCall(tc)), "toolCalls");
        AgentEvent e = new AgentEvent.MessageEnd(msg);
        // 去色与有色剥离后文本一致，证明渲染器仅受 Style 影响而非直接读环境；且纯函数确定性
        assertEquals(strip(EventRenderer.render(e, s1)), strip(EventRenderer.render(e, s2)));
        assertEquals("→ read a.txt", strip(EventRenderer.render(e, s1)));
        assertEquals("→ read a.txt", strip(EventRenderer.render(e, s2)));
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
}
