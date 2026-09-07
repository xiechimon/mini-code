package dev.minicode.cli;

import dev.minicode.agent.AgentEvent;
import dev.minicode.ai.Message;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;

/**
 * 事件渲染器：纯函数，输入 AgentEvent 与样式开关（必要时含耗时），输出待打印文本。
 * <p>
 * 职责：收敛原来散在 Main.printEvent 的轮首提示、助手文本、工具调用行、工具结果行、完成行渲染。
 * 约束：不读环境、不碰时钟；耗时由调用方注入，工具次数由调用方基于事件流累计后注入。
 * </p>
 * 对应 spec：事件渲染面；输入为 agent 事件与样式对象，输出为文本；入口类只做组装。
 */
public final class EventRenderer {

    /** 工具结果截断长度，与 Main 原有逻辑保持等价 */
    private static final int TOOL_OUTPUT_TRUNCATE = 800;

    /** ANSI：粗体（用于横幅名称） */
    private static final String ANSI_BOLD = "\u001B[1m";
    /** ANSI：暗灰（用于横幅其余部分，亮黑 90m） */
    private static final String ANSI_DIM = "\u001B[90m";
    /** ANSI：重置 */
    private static final String ANSI_RESET = "\u001B[0m";
    /** 分隔符 */
    private static final String DOT = " · ";

    private EventRenderer() {
    }

    /**
     * 横幅单行渲染：mini-code · provider/model · 工作目录
     * <p>
     * 有色模式：名称粗体、其余暗灰；去色模式：纯文本。
     * 纯函数：不读环境，样式由调用方通过 Style 传入。
     * </p>
     *
     * @param provider 提供方（如 opencode-go）
     * @param modelId  模型名（如 kimi-k2.6）
     * @param workdir  工作目录
     * @param style    样式开关
     * @return 单行横幅文本
     */
    public static String renderBanner(String provider, String modelId, Path workdir, Style style) {
        if (style == null) style = Style.PLAIN;
        String prov = provider != null ? provider : "unknown";
        String mod = modelId != null ? modelId : "unknown";
        String dir = workdir != null ? workdir.toString() : "";
        String plain = "mini-code" + DOT + prov + "/" + mod + DOT + dir;
        if (!style.colorEnabled()) {
            return plain;
        }
        // 有色：名称粗体，其余暗灰
        return ANSI_BOLD + "mini-code" + ANSI_RESET + ANSI_DIM + DOT + prov + "/" + mod + DOT + dir + ANSI_RESET;
    }

    /**
     * 纯函数渲染入口（不涉及时耗）。
     *
     * @param event 事件
     * @param style 样式开关（决定是否允许 ANSI，供 02 的四色接入）
     * @return 待打印文本，空字符串表示该事件无需输出（调用方应跳过打印）
     */
    public static String render(AgentEvent event, Style style) {
        return render(event, style, null, -1, -1);
    }

    /**
     * 纯函数渲染入口（带耗时注入）。
     * <p>
     * 本方法为兼容旧调用保留：未显式传入轮数/工具次数时，将从 AgentEnd 的 messages 推断。
     * 新代码应优先使用 {@link #render(AgentEvent, Style, Duration, int, int)} 显式注入计数。
     * </p>
     *
     * @param event   事件
     * @param style   样式开关
     * @param elapsed 本轮耗时（可为 null，未到轮末时无意义）
     * @return 待打印文本，空字符串表示无需输出
     */
    public static String render(AgentEvent event, Style style, Duration elapsed) {
        return render(event, style, elapsed, -1, -1);
    }

    /**
     * 纯函数渲染入口（带耗时与计数注入）。
     * <p>
     * 轮末统计渲染为：N 轮 · M 次工具 · X.Xs，耗时格式化为一位小数秒。
     * 渲染器不碰时钟，耗时必须由调用方计时后传入；工具次数由调用方基于事件流累计后传入。
     * 若 turns/toolCalls 为负数则尝试从 AgentEnd 的消息列表推断，保证纯函数可测与兼容旧调用。
     * </p>
     *
     * @param event     事件
     * @param style     样式开关
     * @param elapsed   本轮耗时（可为 null，视为 0）
     * @param turns     轮数（-1 表示推断）
     * @param toolCalls 工具次数（-1 表示推断）
     * @return 待打印文本
     */
    public static String render(AgentEvent event, Style style, Duration elapsed, int turns, int toolCalls) {
        // 防御：style 可能为 null 时按去色处理（保持纯文本）
        if (style == null) style = Style.PLAIN;

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
            // 未来有色：成功绿 / 失败红，本票保持等价（不动 02 规则）
            return line;
        } else if (event instanceof AgentEvent.AgentEnd ae) {
            // 03：轮末统计 N 轮 · M 次工具 · X.Xs（耗时由入口注入，计数由事件流累计）
            int effTurns = turns;
            int effTools = toolCalls;
            if (effTurns < 0) {
                effTurns = inferTurns(ae);
            }
            if (effTools < 0) {
                effTools = inferToolCalls(ae);
            }
            String elapsedStr = formatElapsed(elapsed);
            String stats = effTurns + " 轮" + DOT + effTools + " 次工具" + DOT + elapsedStr;
            // 去色与有色文案一致（纯文本语义相同），有色可在外层对该行做样式包装，保持本票行为等价
            // 为贴合原有完成行的换行习惯，前置换行
            return "\n" + stats;
        } else if (event instanceof AgentEvent.ToolStart) {
            return "";
        } else if (event instanceof AgentEvent.TurnEnd) {
            return "";
        } else if (event instanceof AgentEvent.AgentStart) {
            return "";
        }
        return "";
    }

    /**
     * 耗时格式化为一位小数秒，如 1.5s、0.1s。
     */
    static String formatElapsed(Duration elapsed) {
        if (elapsed == null) elapsed = Duration.ZERO;
        double seconds = elapsed.toMillis() / 1000.0;
        // 使用 Locale.ROOT 保证小数点为 .
        return String.format(Locale.ROOT, "%.1fs", seconds);
    }

    /**
     * 从 AgentEnd 消息列表推断轮数（以 assistant 消息数为近似）。
     */
    private static int inferTurns(AgentEvent.AgentEnd ae) {
        if (ae.messages() == null) return 0;
        long c = ae.messages().stream().filter(m -> m.role == Message.Role.assistant).count();
        return (int) c;
    }

    /**
     * 从 AgentEnd 消息列表推断工具次数（以 toolResult 消息数为准）。
     */
    private static int inferToolCalls(AgentEvent.AgentEnd ae) {
        if (ae.messages() == null) return 0;
        long c = ae.messages().stream().filter(m -> m.role == Message.Role.toolResult).count();
        return (int) c;
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
