package dev.minicode.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.minicode.agent.AgentEvent;
import dev.minicode.ai.Message;

import java.time.Duration;
import java.util.Map;

/**
 * 事件渲染器：纯函数，输入 AgentEvent 与样式开关（必要时含耗时），输出待打印文本。
 * <p>
 * 本票（02）落地：工具调用摘要、四色角色、工具结果首行折叠与失败封顶。
 * 约束：不读环境、不碰时钟；耗时由调用方注入（完成行仍沿用旧文案，保持与 03 分离）。
 * </p>
 * 对应 spec：事件渲染面；输入为 agent 事件与样式对象，输出为文本；入口类只做组装。
 */
public final class EventRenderer {

    /** 参数摘要截断长度 */
    private static final int PARAM_TRUNCATE = 60;
    /** 失败结果封顶长度 */
    private static final int FAILURE_TRUNCATE = 500;

    // ANSI 角色色（零依赖手写）
    private static final String ANSI_RESET = "\u001B[0m";
    private static final String ANSI_CYAN = "\u001B[36m";   // 工具名青
    private static final String ANSI_GRAY = "\u001B[90m";   // 参数/辅助信息暗灰（bright black）
    private static final String ANSI_GREEN = "\u001B[32m";  // 成功绿
    private static final String ANSI_RED = "\u001B[31m";    // 失败红

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EventRenderer() {
    }

    /**
     * 纯函数渲染入口（不涉及时耗）。
     *
     * @param event 事件
     * @param style 样式开关（决定是否允许 ANSI，供四色接入）
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
            String text = "\n[第 " + t.turn() + " 轮] 思考中...";
            return maybeColor(text, ANSI_GRAY, style);
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
                String summary = summarize(tc);
                // 工具名青、参数暗灰
                String namePart = maybeColor(tc.name, ANSI_CYAN, style);
                String line;
                if (summary == null || summary.isEmpty()) {
                    line = "→ " + namePart;
                } else {
                    String paramPart = maybeColor(summary, ANSI_GRAY, style);
                    line = "→ " + namePart + " " + paramPart;
                }
                sb.append(line);
            }
            if (sb.length() == 0) return "";
            return sb.toString();
        } else if (event instanceof AgentEvent.ToolResultEvent tr) {
            return renderToolResult(tr, style);
        } else if (event instanceof AgentEvent.AgentEnd ae) {
            // 03 横幅/轮末统计不动，保持旧文案与无色（与 03 分离）
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

    /**
     * 工具结果行渲染。
     * <ul>
     * <li>成功：首行 + 多行追加「… 共 N 行」（成功绿，辅助信息暗灰）</li>
     * <li>失败：全文封顶 500 字符（失败红）</li>
     * </ul>
     */
    private static String renderToolResult(AgentEvent.ToolResultEvent tr, Style style) {
        String toolName = tr.toolCall().name;
        String toolNameColored = maybeColor(toolName, ANSI_CYAN, style);
        String prefix = "← " + toolNameColored + (tr.isError() ? " [失败]" : "") + ": ";

        String output = tr.output();
        if (output == null) output = "";

        if (tr.isError()) {
            // 失败：全文封顶 500 字符，失败红
            String capped = output.length() <= FAILURE_TRUNCATE ? output : output.substring(0, FAILURE_TRUNCATE) + "...";
            return prefix + maybeColor(capped, ANSI_RED, style);
        } else {
            // 成功：首行 + 多行折叠
            if (output.isEmpty()) {
                return prefix + maybeColor("", ANSI_GREEN, style);
            }
            // 按通用换行符切分
            String[] parts = output.split("\\R");
            // 空字符串的 split 行为特殊："" -> [""]，已在 isEmpty 分支处理；此处 parts 至少长度 1
            String firstLine = parts.length > 0 ? parts[0] : "";
            int totalLines = parts.length;
            // 统计行数：用 split("\\R") 已丢弃末尾空行，视为可视行数；去色与有色保持一致
            if (totalLines > 1) {
                String suffixPlain = " … 共 " + totalLines + " 行";
                String firstColored = maybeColor(firstLine, ANSI_GREEN, style);
                String suffixColored = maybeColor(suffixPlain, ANSI_GRAY, style);
                // 去色时两者拼接即为 plain；有色时分别为绿与暗灰
                // 注意：当去色时 maybeColor 返回原文本，拼接结果等于 plain 预期
                if (!style.colorEnabled()) {
                    return prefix + firstLine + suffixPlain;
                }
                return prefix + firstColored + suffixColored;
            } else {
                return prefix + maybeColor(firstLine, ANSI_GREEN, style);
            }
        }
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
            // 缺 path 时视为空摘要（不倾倒 JSON，保持一行摘要的简洁）
            return "";
        } else if ("bash".equals(name)) {
            String cmd = extractStringField(tc, "command");
            if (cmd == null) {
                // 回退：取紧凑 JSON 的 command 字段或整体
                cmd = "";
                if (tc.argumentsJson != null && !"null".equals(tc.argumentsJson)) {
                    // 若 argumentsJson 非空但未能提取 command，尝试直接用其裁剪后的紧凑 JSON
                    // 但 bash 场景更期望 command 文本，此处返回空或裁剪后 JSON 均可；选空以保持与 path 缺失一致
                }
            }
            if (cmd == null) cmd = "";
            if (cmd.length() > PARAM_TRUNCATE) {
                return cmd.substring(0, PARAM_TRUNCATE) + "...";
            }
            return cmd;
        } else {
            // 其他工具：紧凑 JSON 截 60
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
            // 尝试压缩：若为 JSON 文本则重序列化为紧凑形式，失败则原样返回
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
        return ansiCode + text + ANSI_RESET;
    }
}
