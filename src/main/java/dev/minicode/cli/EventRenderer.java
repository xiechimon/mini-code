package dev.minicode.cli;

import dev.minicode.agent.AgentEvent;
import dev.minicode.ai.Message;

import java.time.Duration;

/**
 * 事件渲染器：纯函数，输入 AgentEvent 与样式开关（必要时含耗时），输出待打印文本。
 * <p>
 * 职责：收敛原来散在 Main.printEvent 的轮首提示、助手文本、工具调用行、工具结果行、完成行渲染。
 * 约束：不读环境、不碰时钟；耗时由调用方注入（当前完成行暂未使用耗时，保持与现状等价，为 03 预留）。
 * </p>
 * 对应 spec：事件渲染面；输入为 agent 事件与样式对象，输出为文本；入口类只做组装。
 */
public final class EventRenderer {

    /** 工具结果截断长度，与 Main 原有逻辑保持等价 */
    private static final int TOOL_OUTPUT_TRUNCATE = 800;

    private EventRenderer() {
    }

    /**
     * 纯函数渲染入口（不涉及时耗）。
     *
     * @param event 事件
     * @param style 样式开关（决定是否允许 ANSI，供 02 的四色接入）
     * @return 待打印文本，空字符串表示该事件无需输出（调用方应跳过打印）
     */
    public static String render(AgentEvent event, Style style) {
        return render(event, style, null);
    }

    /**
     * 纯函数渲染入口（带耗时注入）。
     * <p>
     * 本票完成行仍沿用“共 N 条消息”文案，保持等价；耗时参数为 03 的轮末统计预留，
     * 当前忽略以满足行为保持，但已实现“耗时由调用方注入、不碰时钟”的纯函数形态。
     * </p>
     *
     * @param event   事件
     * @param style   样式开关
     * @param elapsed 本轮耗时（可为 null，未到轮末时无意义）
     * @return 待打印文本，空字符串表示无需输出
     */
    public static String render(AgentEvent event, Style style, Duration elapsed) {
        // 防御：style 可能为 null 时按去色处理（保持纯文本）
        if (style == null) style = Style.PLAIN;
        // elapsed 仅为 03 预留，本票不使用，保持等价

        if (event instanceof AgentEvent.TurnStart t) {
            // 现状：\n[第 N 轮] 思考中...
            // 未来有色可对该行加暗灰，本票保持等价
            return "\n[第 " + t.turn() + " 轮] 思考中...";
        } else if (event instanceof AgentEvent.MessageEnd m) {
            Message msg = m.message();
            if (msg.role != Message.Role.assistant) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            String txt = msg.text();
            boolean hasText = txt != null && !txt.isBlank();
            if (hasText) {
                sb.append(txt);
            }
            for (Message.ToolCall tc : msg.toolCalls()) {
                if (sb.length() > 0) sb.append("\n");
                // 现状直接拼接 argumentsJson，保持等价；02 将替换为摘要规则
                String argsJson = tc.argumentsJson;
                if (argsJson == null) argsJson = "null";
                sb.append("→ 调用工具: ").append(tc.name).append(" ").append(argsJson);
            }
            if (sb.length() == 0) return "";
            return sb.toString();
        } else if (event instanceof AgentEvent.ToolResultEvent tr) {
            String out = truncate(tr.output(), TOOL_OUTPUT_TRUNCATE);
            String line = "← " + tr.toolCall().name + (tr.isError() ? " [失败]" : "") + ": " + out;
            // 未来有色：成功绿 / 失败红，本票保持等价
            return line;
        } else if (event instanceof AgentEvent.AgentEnd ae) {
            // 现状：\n[mini-code] 完成（共 N 条消息）
            // 未来 03 将替换为 “N 轮 · M 次工具 · X.Xs” 并使用 elapsed
            return "\n[mini-code] 完成（共 " + ae.messages().size() + " 条消息）";
        } else if (event instanceof AgentEvent.ToolStart) {
            return "";
        } else if (event instanceof AgentEvent.TurnEnd) {
            return "";
        } else if (event instanceof AgentEvent.AgentStart) {
            return "";
        }
        return "";
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }

    /**
     * 未来着色辅助（手写 ANSI，零依赖）。
     * 本票暂不启用，预留给 02 的四色接入，保持零新依赖与纯函数特性。
     */
    static String maybeColor(String text, String ansiCode, Style style) {
        if (!style.colorEnabled() || ansiCode == null) return text;
        return ansiCode + text + "\u001B[0m";
    }
}
