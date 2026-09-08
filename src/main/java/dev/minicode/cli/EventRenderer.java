package dev.minicode.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.minicode.agent.AgentEvent;
import dev.minicode.ai.Message;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/**
 * 事件渲染器：纯函数，输入 AgentEvent 与样式开关（必要时含耗时），输出待打印文本。
 * <p>
 * 职责：收敛原来散在 Main.printEvent 的轮首提示、助手文本、工具调用行、工具结果行、完成行渲染。
 * 融合两票：02 的工具行摘要/折叠/失败封顶与四色 + 03 的横幅一行/四参 render/耗时格式化/推断辅助；完成行采用 03 的统计格式。
 * 约束：不读环境、不碰时钟；耗时由调用方注入，工具次数由调用方基于事件流累计后注入。
 * </p>
 * 对应 spec：事件渲染面；输入为 agent 事件与样式对象，输出为文本；入口类只做组装。
 * <p>
 * 对齐 pi 源：pi-tui 渲染层（行式事件 → 终端文本的纯函数渲染）、
 * 参考 pi/packages/tui/src/components/provider-attribution.ts 的四色角色与 provider/model 展示约定；
 * ANSI 常量唯一定义见 {@link Style}。
 * </p>
 */
public final class EventRenderer {

    /** 参数摘要截断长度 */
    private static final int PARAM_TRUNCATE = 60;
    /** 失败结果封顶长度 */
    private static final int FAILURE_TRUNCATE = 500;

    /** 分隔符 */
    private static final String DOT = " · ";

    /** 中断标记 */
    private static final String ABORTED_MARK = "⏹ 已中断";

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
        // 有色：名称粗体，其余暗灰（ANSI 常量收敛至 Style）
        return Style.ANSI_BOLD + "mini-code" + Style.ANSI_RESET + Style.ANSI_DIM + DOT + prov + "/" + mod + DOT + dir + Style.ANSI_RESET;
    }

    /**
     * 纯函数渲染入口（不涉及时耗，宽度默认 80）。
     *
     * @param event 事件
     * @param style 样式开关（决定是否允许 ANSI，供四色接入）
     * @return 待打印文本，空字符串表示该事件无需输出（调用方应跳过打印）
     */
    public static String render(AgentEvent event, Style style) {
        return render(event, style, TurnStats.inferred(null), AnsiTextUtil.DEFAULT_WIDTH);
    }

    /**
     * 纯函数渲染入口（带宽度注入，不涉及时耗）。
     *
     * @param event 事件
     * @param style 样式开关
     * @param width 可用宽度（注入，交互取终端宽度、管道 80）
     * @return 待打印文本
     */
    public static String render(AgentEvent event, Style style, int width) {
        return render(event, style, TurnStats.inferred(null), width);
    }

    /**
     * 纯函数渲染入口（带耗时注入，宽度默认 80）。
     * <p>
     * 本方法为兼容旧调用保留：未显式传入轮数/工具次数时，将从 AgentEnd 的 messages 推断。
     * 新代码应优先使用 {@link #render(AgentEvent, Style, TurnStats)} 显式注入计数。
     * </p>
     *
     * @param event   事件
     * @param style   样式开关
     * @param elapsed 本轮耗时（可为 null，未到轮末时无意义）
     * @return 待打印文本，空字符串表示无需输出
     */
    public static String render(AgentEvent event, Style style, Duration elapsed) {
        return render(event, style, TurnStats.inferred(elapsed), AnsiTextUtil.DEFAULT_WIDTH);
    }

    /**
     * 纯函数渲染入口（带耗时与宽度注入）。
     */
    public static String render(AgentEvent event, Style style, Duration elapsed, int width) {
        return render(event, style, TurnStats.inferred(elapsed), width);
    }

    /**
     * 纯函数渲染入口（带耗时与计数注入，旧四参兼容，宽度默认 80）。
     * <p>
     * 轮末统计渲染为：N 轮 · M 次工具 · X.Xs，耗时格式化为一位小数秒。
     * 渲染器不碰时钟，耗时必须由调用方计时后传入；工具次数由调用方基于事件流累计后传入。
     * 若 turns/toolCalls 为负数则尝试从 AgentEnd 的消息列表推断，保证纯函数可测与兼容旧调用。
     * </p>
     *
     * @param event     事件
     * @param style     样式开关
     * @param elapsed   本轮耗时（可为 null，视为 0）
     * @param turns     轮数（-1 表示推断，推荐改用 {@link TurnStats}）
     * @param toolCalls 工具次数（-1 表示推断，推荐改用 {@link TurnStats}）
     * @return 待打印文本
     */
    public static String render(AgentEvent event, Style style, Duration elapsed, int turns, int toolCalls) {
        return render(event, style, new TurnStats(elapsed, turns, toolCalls), AnsiTextUtil.DEFAULT_WIDTH);
    }

    /**
     * 纯函数渲染入口（带耗时、计数与宽度注入，旧五参兼容）。
     */
    public static String render(AgentEvent event, Style style, Duration elapsed, int turns, int toolCalls, int width) {
        return render(event, style, new TurnStats(elapsed, turns, toolCalls), width);
    }

    /**
     * 纯函数渲染入口（收敛 Data Clumps：耗时与计数收拢为 {@link TurnStats}，宽度默认 80）。
     * <p>
     * 轮末统计渲染为：N 轮 · M 次工具 · X.Xs，其中「M 次工具」段用成功绿、其余（N 轮/耗时）用暗灰；
     * 去色模式纯文本。耗时格式化为一位小数秒，渲染器不碰时钟，计数由事件流累计后注入。
     * 若 {@link TurnStats#inferTurns()} / {@link TurnStats#inferTools()} 为真则从 AgentEnd 消息推断。
     * </p>
     *
     * @param event 事件
     * @param style 样式开关
     * @param stats 轮末统计（可为 null，视为推断）
     * @return 待打印文本
     */
    public static String render(AgentEvent event, Style style, TurnStats stats) {
        return render(event, style, stats, AnsiTextUtil.DEFAULT_WIDTH);
    }

    // ——— 流式重绘与中断（04 票）———

    /**
     * 流式增量渲染：原样直出 + 宽度感知行数记账，首个片段覆盖「思考中」占位行。
     * <p>
     * 保持渲染器纯函数缝不破坏：状态由调用方持有（Main 的 StreamState），此处仅更新并返回带 CUU 的文本。
     * 首个 MessageUpdate 到达时前置「光标上移 1 行+清行」({@code \u001B[1A\u001B[2K}) 覆盖 TurnStart 占位行；
     * 后续片段直接原样直出。管道模式专用：state 为 null 时仅原样返回 delta（无控制序列）；
     * 交互式路径必须传非 null 的 StreamState，否则首片段占位行未被覆盖。
     * </p>
     */
    /**
     * 流式感知的 AgentEnd 渲染：复用既有统计逻辑，aborted 时追加标记。
     * AgentEnd 本身不涉及重绘（重绘已在 MessageEnd 完成），此处仅复用统计渲染（已含 aborted 标记），不重复追加。
     */
    private static String buildMessageBody(Message msg, Style style, int width) {
        int w = AnsiTextUtil.normalizeWidth(width);
        if (style == null) style = Style.PLAIN;
        StringBuilder sb = new StringBuilder();
        String txt = msg.text();
        boolean hasText = txt != null && !txt.isBlank();
        if (hasText) {
            String renderedBody = MarkdownRenderer.render(txt, style, w);
            if (renderedBody != null && !renderedBody.isEmpty()) {
                sb.append(renderedBody);
            } else {
                sb.append(txt);
            }
        }
        for (Message.ToolCall tc : msg.toolCalls()) {
            if (sb.length() > 0) sb.append("\n");
            String summary = summarize(tc);
            String namePart = maybeColor(tc.name, Style.ANSI_CYAN, style);
            String line;
            if (summary == null || summary.isEmpty()) {
                line = "→ " + namePart;
            } else {
                String paramPart = maybeColor(summary, Style.ANSI_GRAY, style);
                line = "→ " + namePart + " " + paramPart;
            }
            sb.append(line);
        }
        boolean isAborted = "aborted".equals(msg.stopReason);
        if (isAborted) {
            String mark = style.colorEnabled() ? Style.ANSI_GRAY + ABORTED_MARK + Style.ANSI_RESET : ABORTED_MARK;
            if (sb.length() > 0) sb.append(" ").append(mark);
            else sb.append(mark);
        }
        // 空且非 aborted 时按旧逻辑返回空
        if (sb.isEmpty()) return "";
        return sb.toString();
    }

    /**
     * 纯函数渲染入口（收敛 Data Clumps + 宽度注入）。
     * <p>
     * 助手消息正文经 {@link MarkdownRenderer} 渲染（样式与宽度注入）；工具结果/轮首/横幅/统计路径不变。
     * 宽度对非正文路径无影响，仍保持纯函数（不读环境、不碰时钟）。
     * </p>
     *
     * @param event 事件
     * @param style 样式开关
     * @param stats 轮末统计（可为 null，视为推断）
     * @param width 可用宽度（注入，交互取终端宽度、管道 80；<=0 按 80 处理）
     * @return 待打印文本
     */
    public static String render(AgentEvent event, Style style, TurnStats stats, int width) {
        // 防御：style 可能为 null 时按去色处理（保持纯文本）；stats 可能为 null 时按推断处理；width 非法时按默认宽度
        if (style == null) style = Style.PLAIN;
        if (stats == null) stats = TurnStats.inferred(null);
        width = AnsiTextUtil.normalizeWidth(width);

        if (event instanceof AgentEvent.TurnStart t) {
            String text = "\n[第 " + t.turn() + " 轮] 思考中...";
            return maybeColor(text, Style.ANSI_GRAY, style);
        } else if (event instanceof AgentEvent.MessageEnd m) {
            Message msg = m.message();
            if (msg.role != Message.Role.assistant) {
                return "";
            }
            String body = buildMessageBody(msg, style, width);
            if (body.isEmpty()) return "";
            return body;
        } else if (event instanceof AgentEvent.ToolResultEvent tr) {
            return renderToolResult(tr, style);
        } else if (event instanceof AgentEvent.AgentEnd ae) {
            // 03：轮末统计 N 轮 · M 次工具 · X.Xs（耗时由入口注入，计数由事件流累计）
            // 规格「成功/失败色也用于轮末统计的对应部分」：M 次工具段用成功绿，其余（N 轮/耗时）保持暗灰；去色纯文本
            int effTurns = stats.inferTurns() ? inferTurns(ae) : stats.turns();
            int effTools = stats.inferTools() ? inferToolCalls(ae) : stats.toolCalls();
            String elapsedStr = formatElapsed(stats.elapsedOrZero());
            String base;
            if (!style.colorEnabled()) {
                String plainStats = effTurns + " 轮" + DOT + effTools + " 次工具" + DOT + elapsedStr;
                base = "\n" + plainStats;
            } else {
                // 有色：N 轮（暗灰）· M 次工具（绿）· 耗时（暗灰），分隔符随相邻段保持暗灰
                String turnsPart = maybeColor(effTurns + " 轮", Style.ANSI_GRAY, style);
                String toolsPart = maybeColor(effTools + " 次工具", Style.ANSI_GREEN, style);
                String elapsedPart = maybeColor(elapsedStr, Style.ANSI_GRAY, style);
                String dotGray = maybeColor(DOT, Style.ANSI_GRAY, style);
                base = "\n" + turnsPart + dotGray + toolsPart + dotGray + elapsedPart;
            }
            boolean hasAborted = ae.messages() != null && ae.messages().stream()
                    .anyMatch(mm -> mm.role == Message.Role.assistant && "aborted".equals(mm.stopReason));
            if (hasAborted) {
                String mark = style.colorEnabled() ? Style.ANSI_GRAY + ABORTED_MARK + Style.ANSI_RESET : ABORTED_MARK;
                return base + " " + mark;
            }
            return base;
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
     * 工具结果行渲染。
     * <ul>
     * <li>成功：首行 + 多行追加「… 共 N 行」（成功绿，辅助信息暗灰）</li>
     * <li>失败：全文封顶 500 字符（失败红）</li>
     * </ul>
     */
    private static String renderToolResult(AgentEvent.ToolResultEvent tr, Style style) {
        String toolName = tr.toolCall().name;
        String toolNameColored = maybeColor(toolName, Style.ANSI_CYAN, style);
        String prefix = "← " + toolNameColored + (tr.isError() ? " [失败]" : "") + ": ";

        String output = tr.output();
        if (output == null) output = "";

        if (tr.isError()) {
            // 失败：全文封顶 500 字符，失败红
            String capped = output.length() <= FAILURE_TRUNCATE ? output : output.substring(0, FAILURE_TRUNCATE) + "...";
            return prefix + maybeColor(capped, Style.ANSI_RED, style);
        } else {
            // 成功：首行 + 多行折叠
            if (output.isEmpty()) {
                return prefix + maybeColor("", Style.ANSI_GREEN, style);
            }
            // 按通用换行符切分
            String[] parts = output.split("\\R");
            String firstLine = parts.length > 0 ? parts[0] : "";
            int totalLines = parts.length;
            if (totalLines > 1) {
                String suffixPlain = " … 共 " + totalLines + " 行";
                String firstColored = maybeColor(firstLine, Style.ANSI_GREEN, style);
                String suffixColored = maybeColor(suffixPlain, Style.ANSI_GRAY, style);
                if (!style.colorEnabled()) {
                    return prefix + firstLine + suffixPlain;
                }
                return prefix + firstColored + suffixColored;
            } else {
                return prefix + maybeColor(firstLine, Style.ANSI_GREEN, style);
            }
        }
    }

    /**
     * 耗时格式化为一位小数秒，如 1.5s、0.1s。
     */
    static String formatElapsed(Duration elapsed) {
        if (elapsed == null) elapsed = Duration.ZERO;
        double seconds = elapsed.toMillis() / 1000.0;
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

    /**
     * 参数摘要规则：
     * <ul>
     * <li>read/write/edit 取 path</li>
     * <li>bash 取 command 截 60 字符</li>
     * <li>其他取紧凑 JSON 截 60 字符</li>
     * </ul>
     */
    static String summarize(Message.ToolCall tc) {
        if (tc == null || tc.name == null) return "";
        String name = tc.name;
        if ("read".equals(name) || "write".equals(name) || "edit".equals(name)) {
            String path = extractStringField(tc, "path");
            if (path != null) return path;
            return "";
        } else if ("bash".equals(name)) {
            String cmd = extractStringField(tc, "command");
            if (cmd == null) {
                cmd = "";
            }
            if (cmd == null) cmd = "";
            if (cmd.length() > PARAM_TRUNCATE) {
                return cmd.substring(0, PARAM_TRUNCATE) + "...";
            }
            return cmd;
        } else {
            String json = buildCompactJson(tc);
            if (json == null) json = "";
            json = json.trim();
            if (json.length() > PARAM_TRUNCATE) {
                return json.substring(0, PARAM_TRUNCATE) + "...";
            }
            return json;
        }
    }

    /**
     * 提取字符串字段：优先 arguments Map，其次解析 argumentsJson。
     */
    private static String extractStringField(Message.ToolCall tc, String field) {
        if (tc.arguments != null) {
            Object v = tc.arguments.get(field);
            if (v instanceof String s) return s;
        }
        if (tc.argumentsJson != null && !"null".equals(tc.argumentsJson) && !tc.argumentsJson.isBlank()) {
            try {
                JsonNode node = MAPPER.readTree(tc.argumentsJson);
                if (node.has(field) && node.get(field).isTextual()) {
                    return node.get(field).asText();
                }
            } catch (Exception ignored) {
                // 解析失败则回退
            }
        }
        return null;
    }

    /**
     * 构建紧凑 JSON：优先序列化 arguments Map，否则使用 argumentsJson 原值。
     */
    private static String buildCompactJson(Message.ToolCall tc) {
        if (tc.arguments != null && !tc.arguments.isEmpty()) {
            try {
                return MAPPER.writeValueAsString(tc.arguments);
            } catch (JsonProcessingException ignored) {
                // 回退到 argumentsJson
            }
        }
        if (tc.argumentsJson != null && !"null".equals(tc.argumentsJson)) {
            String raw = tc.argumentsJson.trim();
            if (raw.isEmpty()) return "";
            try {
                JsonNode node = MAPPER.readTree(raw);
                return MAPPER.writeValueAsString(node);
            } catch (Exception ignored) {
                return raw;
            }
        }
        return "";
    }

    /**
     * 着色辅助（手写 ANSI，零依赖）。
     */
    static String maybeColor(String text, String ansiCode, Style style) {
        if (!style.colorEnabled() || ansiCode == null) return text;
        return ansiCode + text + Style.ANSI_RESET;
    }
}
