package dev.minicode.cli;

import dev.minicode.agent.AgentEvent;
import dev.minicode.ai.Message;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 事件渲染器单测：纯函数输出字符串断言，覆盖现有文案等价与去色/有色两态。
 * <p>
 * 约束：渲染器不读环境、不碰时钟；耗时由调用方注入（本票完成行暂忽略耗时，保持等价）。
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

    // —— 完成行 ——

    @Test
    void agentEndRenders() {
        List<Message> messages = List.of(Message.user("hi"), Message.assistant(List.of(Message.Content.text("ok")), "end"));
        AgentEvent e = new AgentEvent.AgentEnd(messages);
        assertEquals("\n[mini-code] 完成（共 2 条消息）", EventRenderer.render(e, plain));
        assertEquals("\n[mini-code] 完成（共 2 条消息）", EventRenderer.render(e, colored));
        // 耗时注入当前不影响输出（为 03 预留），保持纯函数可测
        assertEquals("\n[mini-code] 完成（共 2 条消息）", EventRenderer.render(e, plain, Duration.ofSeconds(2)));
        assertEquals("\n[mini-code] 完成（共 2 条消息）", EventRenderer.render(e, colored, Duration.ofMillis(500)));
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
        // 本票 Style 暂不影响渲染文本，故不同 Style 输出一致，证明渲染器未直接读环境
        Style s1 = Style.detect(Map.of("NO_COLOR", "1"), true);
        Style s2 = Style.detect(Map.of(), true);
        AgentEvent e = new AgentEvent.TurnStart(1);
        // 虽然 s1/s2 样式不同，渲染输出保持等价（行为保持）
        assertEquals(EventRenderer.render(e, s1), EventRenderer.render(e, s2));
    }
}
