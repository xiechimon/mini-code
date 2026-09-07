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
 * 事件渲染器单测：纯函数输出字符串断言，覆盖现有文案等价与去色/有色两态。
 * <p>
 * 约束：渲染器不读环境、不碰时钟；耗时由调用方注入，工具次数由事件流累计后注入。
 * </p>
 */
class EventRendererTest {

    private final Style plain = Style.plain();
    private final Style colored = Style.colored();

    // —— 轮首提示 ——

    @Test
    void turnStartRenders() {
        AgentEvent e = new AgentEvent.TurnStart(1);
        String out = EventRenderer.render(e, plain);
        assertEquals("\n[第 1 轮] 思考中...", out);
        // 有色与去色本票保持等价（行为保持，不引入新颜色）
        assertEquals(out, EventRenderer.render(e, colored));
        assertEquals(out, EventRenderer.render(e, plain, Duration.ofMillis(123)));
        assertEquals(out, EventRenderer.render(e, colored, Duration.ofSeconds(1)));
    }

    @Test
    void turnStartMultipleRounds() {
        assertEquals("\n[第 2 轮] 思考中...", EventRenderer.render(new AgentEvent.TurnStart(2), plain));
        assertEquals("\n[第 10 轮] 思考中...", EventRenderer.render(new AgentEvent.TurnStart(10), plain));
    }

    // —— 助手文本 ——

    @Test
    void messageEndAssistantText() {
        Message msg = Message.assistant(List.of(Message.Content.text("你好，世界")), "end");
        AgentEvent e = new AgentEvent.MessageEnd(msg);
        assertEquals("你好，世界", EventRenderer.render(e, plain));
        assertEquals("你好，世界", EventRenderer.render(e, colored));
    }

    @Test
    void messageEndBlankTextNoOutputWhenNoToolCalls() {
        Message msg = Message.assistant(List.of(Message.Content.text("   ")), "end");
        AgentEvent e = new AgentEvent.MessageEnd(msg);
        assertEquals("", EventRenderer.render(e, plain));
        assertEquals("", EventRenderer.render(e, colored));
    }

    // —— 工具调用行 ——

    @Test
    void messageEndToolCallOnly() {
        Message.ToolCall tc = new Message.ToolCall("1", "read", Map.of("path", "a.txt"), "{\"path\":\"a.txt\"}");
        Message msg = Message.assistant(List.of(Message.Content.toolCall(tc)), "toolCalls");
        AgentEvent e = new AgentEvent.MessageEnd(msg);
        assertEquals("→ 调用工具: read {\"path\":\"a.txt\"}", EventRenderer.render(e, plain));
        assertEquals("→ 调用工具: read {\"path\":\"a.txt\"}", EventRenderer.render(e, colored));
    }

    @Test
    void messageEndTextAndToolCalls() {
        Message.ToolCall tc1 = new Message.ToolCall("1", "bash", Map.of("command", "ls -l"), "{\"command\":\"ls -l\"}");
        Message.ToolCall tc2 = new Message.ToolCall("2", "read", Map.of("path", "README.md"), "{\"path\":\"README.md\"}");
        Message msg = Message.assistant(List.of(
                Message.Content.text("准备执行"),
                Message.Content.toolCall(tc1),
                Message.Content.toolCall(tc2)
        ), "toolCalls");
        AgentEvent e = new AgentEvent.MessageEnd(msg);
        String expected = "准备执行\n→ 调用工具: bash {\"command\":\"ls -l\"}\n→ 调用工具: read {\"path\":\"README.md\"}";
        assertEquals(expected, EventRenderer.render(e, plain));
        assertEquals(expected, EventRenderer.render(e, colored));
    }

    @Test
    void messageEndNonAssistantIgnored() {
        Message msg = Message.user("hello");
        AgentEvent e = new AgentEvent.MessageEnd(msg);
        assertEquals("", EventRenderer.render(e, plain));
        assertEquals("", EventRenderer.render(e, colored));
    }

    // —— 工具结果行 ——

    @Test
    void toolResultSuccess() {
        Message.ToolCall tc = new Message.ToolCall("1", "read", Map.of("path", "a.txt"), "{}");
        AgentEvent e = new AgentEvent.ToolResultEvent(tc, "文件内容", false);
        assertEquals("← read: 文件内容", EventRenderer.render(e, plain));
        assertEquals("← read: 文件内容", EventRenderer.render(e, colored));
    }

    @Test
    void toolResultFailure() {
        Message.ToolCall tc = new Message.ToolCall("1", "bash", Map.of("command", "rm"), "{}");
        AgentEvent e = new AgentEvent.ToolResultEvent(tc, "error: no such file", true);
        assertEquals("← bash [失败]: error: no such file", EventRenderer.render(e, plain));
        assertEquals("← bash [失败]: error: no such file", EventRenderer.render(e, colored));
    }

    @Test
    void toolResultTruncates800() {
        Message.ToolCall tc = new Message.ToolCall("1", "read", Map.of(), "{}");
        String longText = "a".repeat(801);
        AgentEvent e = new AgentEvent.ToolResultEvent(tc, longText, false);
        String out = EventRenderer.render(e, plain);
        String expected = "← read: " + "a".repeat(800) + "...";
        assertEquals(expected, out);
        // 恰好 800 不截断
        String exact = "a".repeat(800);
        AgentEvent e2 = new AgentEvent.ToolResultEvent(tc, exact, false);
        assertEquals("← read: " + exact, EventRenderer.render(e2, plain));
        // null 输出视为空
        AgentEvent e3 = new AgentEvent.ToolResultEvent(tc, null, false);
        assertEquals("← read: ", EventRenderer.render(e3, plain));
    }

    @Test
    void toolResultPlainAndColoredSameForBaseline() {
        // 本票行为保持：有色与去色输出文本等价（仅探测开关不同，渲染暂不分色）
        Message.ToolCall tc = new Message.ToolCall("1", "write", Map.of("path", "out.txt"), "{\"path\":\"out.txt\"}");
        AgentEvent e = new AgentEvent.ToolResultEvent(tc, "ok", false);
        assertEquals(EventRenderer.render(e, plain), EventRenderer.render(e, colored));
        // 去色输出不含 ANSI
        assertFalse(EventRenderer.render(e, plain).contains("\u001B["));
        // 本票有色也暂不含 ANSI（保持等价），但仍需保证剥离后一致
        String coloredOut = EventRenderer.render(e, colored);
        String stripped = coloredOut.replaceAll("\u001B\\[[0-9;]*m", "");
        assertEquals(EventRenderer.render(e, plain), stripped);
    }

    // —— 横幅单行 ——

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

    // —— 完成行（轮末统计） ——

    @Test
    void agentEndRendersWithExplicitStats() {
        // 空消息列表，显式注入轮数/工具次数/耗时
        AgentEvent e = new AgentEvent.AgentEnd(List.of());
        // 显式注入 3 轮 5 次工具 1.5s
        String outPlain = EventRenderer.render(e, plain, Duration.ofMillis(1500), 3, 5);
        assertEquals("\n3 轮 · 5 次工具 · 1.5s", outPlain);
        assertFalse(outPlain.contains("\u001B["));
        String outColored = EventRenderer.render(e, colored, Duration.ofMillis(1500), 3, 5);
        // 有色与去色文案剥离后一致（本票统计行不额外着色，保持纯文本语义一致）
        assertEquals(outPlain, outColored.replaceAll("\u001B\\[[0-9;]*m", ""));
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
        assertEquals("\n1 轮 · 0 次工具 · 0.5s", EventRenderer.render(e, colored, Duration.ofMillis(500)));
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
        assertEquals(p, c.replaceAll("\u001B\\[[0-9;]*m", ""));
        assertFalse(p.contains("\u001B["));
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
        assertEquals("", EventRenderer.render(new AgentEvent.AgentStart(), colored));
        assertEquals("", EventRenderer.render(new AgentEvent.ToolStart(new Message.ToolCall("1", "read", Map.of(), "{}")), plain));
        Message msg = Message.assistant(List.of(Message.Content.text("hi")), "end");
        assertEquals("", EventRenderer.render(new AgentEvent.TurnEnd(msg, List.of()), plain));
    }

    // —— 纯函数特性 ——

    @Test
    void rendererIsPureFunctionDeterministic() {
        AgentEvent e = new AgentEvent.TurnStart(3);
        String a = EventRenderer.render(e, plain);
        String b = EventRenderer.render(e, plain);
        assertEquals(a, b, "纯函数应对相同输入产生相同输出");
        // 不随时间变化（多次调用间隔不影响）
        String c = EventRenderer.render(e, plain, Duration.ofSeconds(10));
        String d = EventRenderer.render(e, plain, Duration.ofMillis(1));
        assertEquals(c, d);
    }

    @Test
    void rendererDoesNotReadEnv() {
        // 通过注入不同 env 的 Style，结果应仅受 Style 影响，而非环境
        Style s1 = Style.detect(Map.of("NO_COLOR", "1"), true);
        Style s2 = Style.detect(Map.of(), true);
        AgentEvent e = new AgentEvent.TurnStart(1);
        //  bien s1/s2 样式不同，但 TurnStart 本票保持等价
        assertEquals(EventRenderer.render(e, s1), EventRenderer.render(e, s2));
        // 横幅与统计的去色/有色仅由 Style 决定，剥离后一致
        Path workdir = Path.of("/tmp/work");
        String b1 = EventRenderer.renderBanner("opencode-go", "kimi-k2.6", workdir, s1);
        String b2 = EventRenderer.renderBanner("opencode-go", "kimi-k2.6", workdir, s2);
        // s1 去色无 ANSI，s2 有色含 ANSI，但剥离后一致
        assertFalse(b1.contains("\u001B["));
        assertEquals(b1, b2.replaceAll("\u001B\\[[0-9;]*m", ""));
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
}
