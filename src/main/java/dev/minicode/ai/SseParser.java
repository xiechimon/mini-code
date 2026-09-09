package dev.minicode.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * SSE 行解析器——纯函数，不碰网络不碰 IO。
 * 输入 SSE 文本行序列，输出解析事件列表（文本增量 / 完成 / 工具调用增量 / 忽略行）。
 * 遵循 OpenAI chat.completions 流协议 + 真实网关 spike 差异。
 * 对应 spec.md Implementation Decisions 所述：data 行→JSON→delta 提取。
 *
 * <p>容错：注释行/空行/非 data 行忽略；非 JSON 行容错；reasoning/usage 等额外字段忽略；
 * 工具调用增量解析但标记为 suppressed（上层拼装，不外发）。</p>
 *
 * 对齐参考：pi-ai 的 AssistantMessageEventStream 文本 delta 逻辑。
 */
public final class SseParser {

    private static final Logger log = LoggerFactory.getLogger(SseParser.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SseParser() {
    }

    /**
     * 解析事件——密封接口，便于单测穷举。
     */
    public sealed interface Event permits TextDelta, ToolCallDelta, Done, Ignored {
    }

    /** 文本增量——可直接上屏的片段 */
    public record TextDelta(String text) implements Event {
    }

    /**
     * 工具调用增量——解析自 delta.tool_calls 的分片。
     * 按 spec 标记为不外发（上层负责拼装 id/name/arguments），此处 suppressed 恒为 true。
     */
    public record ToolCallDelta(int index, String id, String name, String argumentsDelta) implements Event {
        /** 是否抑制外发（恒 true） */
        public boolean suppressed() {
            return true;
        }
    }

    /** 完成——对应 data: [DONE] 或 finish_reason 非空的终止分片 */
    public record Done(String raw) implements Event {
        public Done() {
            this("[DONE]");
        }
    }

    /** 可忽略行——注释/空行/非 JSON/无增量的 JSON 等 */
    public record Ignored(String raw, String reason) implements Event {
    }

    /**
     * 纯函数入口：行序列 → 事件列表。
     * 输入的每条字符串视为按行读取的结果（不含换行符），空字符串代表空行。
     * 兼容两种调用形态：
     * - 行序列（常见单测写法）：List.of("data: {...}", "", "data: [DONE]")，每项是一行
     * - 块序列（TCP chunk 切分）：每项是任意字节块，可能含 \n 且可能在行中切断
     * 内部统一重组为行流后再按 SSE 事件边界（空行）分发，满足“跨块 data 拼接”需求。
     *
     * @param lines SSE 文本行或块序列
     * @return 解析事件（顺序与输入对应）
     */
    public static List<Event> parse(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }
        // 1) 重组为完整行流：区分“行模式”与“块模式”
        List<String> normalized = normalizeToLines(lines);
        // 2) 按 SSE 语义缓冲 data 行，直到空行分发
        List<Event> out = new ArrayList<>();
        StringBuilder dataBuffer = new StringBuilder();
        boolean hasPendingData = false;
        boolean doneSeen = false;
        for (String rawLine : normalized) {
            if (doneSeen) {
                // [DONE] 后的所有行均视为忽略（包含网关额外 data: {"choices":[],"cost":"0"}）
                if (!rawLine.isEmpty()) {
                    out.add(new Ignored(rawLine, "after-done"));
                } else {
                    out.add(new Ignored(rawLine, "after-done-empty"));
                }
                continue;
            }
            if (rawLine.isEmpty()) {
                // 空行：SSE 事件边界
                if (hasPendingData) {
                    boolean hitDone = flushDataBuffer(dataBuffer, out);
                    dataBuffer.setLength(0);
                    hasPendingData = false;
                    if (hitDone) {
                        doneSeen = true;
                    }
                } else {
                    out.add(new Ignored(rawLine, "empty-line"));
                }
                continue;
            }
            if (rawLine.startsWith(":")) {
                // 注释 / 心跳（如 ": keep-alive"）
                out.add(new Ignored(rawLine, "comment"));
                continue;
            }
            int colon = rawLine.indexOf(':');
            if (colon == -1) {
                out.add(new Ignored(rawLine, "no-colon"));
                continue;
            }
            String field = rawLine.substring(0, colon);
            String value = rawLine.substring(colon + 1);
            // 按 SSE 规范，去掉冒号后一个可选空格
            if (value.startsWith(" ")) {
                value = value.substring(1);
            }
            // 仅保留 data 字段到缓冲；其余字段（event/id/retry）视为忽略但不触发分发
            if ("data".equals(field)) {
                if (value.isEmpty()) {
                    // 空 data 字段：标记待分发，缓冲保持空但需触发 empty-data 忽略
                    hasPendingData = true;
                    if (dataBuffer.length() > 0) {
                        dataBuffer.append('\n');
                    }
                    // 不追加内容，保持缓冲为空但 hasPendingData 为 true，flush 时会产生 empty-data
                } else {
                    if (hasPendingData && dataBuffer.length() > 0) {
                        dataBuffer.append('\n');
                    }
                    dataBuffer.append(value);
                    hasPendingData = true;
                }
                // 注意：不立即分发，等空行再 flush，符合 SSE 事件模型；
                // 兼容行模式与块模式的差异通过归一化已抹平。
            } else {
                out.add(new Ignored(rawLine, "field-" + field));
            }
        }
        // 流结束时若仍有未分发的 data（缺少 trailing 空行的情况），补 flush
        if (!doneSeen && hasPendingData) {
            flushDataBuffer(dataBuffer, out);
        }
        return List.copyOf(out);
    }

    /**
     * 将输入的行/块序列归一化为行流。
     * 启发式：若任一项含换行符，视为块序列（TCP chunk），拼接后按行切分；
     * 否则视为行序列（每项一行），保持原顺序。
     */
    private static List<String> normalizeToLines(List<String> input) {
        boolean anyContainsNewline = false;
        for (String s : input) {
            if (s != null && (s.contains("\n") || s.contains("\r"))) {
                anyContainsNewline = true;
                break;
            }
        }
        if (!anyContainsNewline) {
            // 行模式：直接将 null 转空串后返回拷贝
            List<String> res = new ArrayList<>(input.size());
            for (String s : input) {
                res.add(s == null ? "" : s);
            }
            return res;
        }
        // 块模式：拼接全部块为原始流，再按行切分（保留空行语义）
        StringBuilder raw = new StringBuilder();
        for (String s : input) {
            if (s != null) raw.append(s);
        }
        String combined = raw.toString();
        // 统一 \r\n 与 \r 为 \n，再按 \n 切分，保留尾空行
        combined = combined.replace("\r\n", "\n").replace("\r", "\n");
        String[] parts = combined.split("\n", -1);
        List<String> lines = new ArrayList<>(parts.length);
        for (String p : parts) {
            lines.add(p);
        }
        // 去掉末尾因 split 产生的额外空行（若原流以 \n 结尾会多一项空串，属真实空行应保留；此处按 SSE 语义保留一方）
        // 实际上 split(-1) 已正确保留所有空行，无需裁剪
        return lines;
    }

    /**
     * 刷新缓冲的 data payload，解析为事件。
     *
     * @return 是否命中 [DONE]（用于标记后续忽略）
     */
    private static boolean flushDataBuffer(StringBuilder buffer, List<Event> out) {
        String payload = buffer.toString();
        // 若缓冲包含多条 data 行（SSE 多 data 语义），按行拆分逐条处理；
        // 单条 JSON 正常情况仅一项，拆分无影响且可兼容异常拼接。
        if (payload.contains("\n")) {
            String[] segments = payload.split("\n", -1);
            boolean done = false;
            for (String seg : segments) {
                // 空的 data 行（如 data: 后无值）视为忽略
                if (seg.isEmpty() && segments.length > 1) {
                    out.add(new Ignored("data: ", "empty-data-in-multi"));
                    continue;
                }
                boolean hit = handleSingleDataPayload(seg, out);
                if (hit) done = true;
                // 一旦命中 DONE，后续段落仍按 after-done 忽略
                if (done) {
                    // 已记录的后续段若还有，未处理部分将在外层 doneSeen 逻辑忽略；
                    // 此处为多段同缓冲内的剩余段，标记忽略
                    // 简化：不额外处理，交由循环继续处理但已知 done
                }
            }
            return done;
        } else {
            return handleSingleDataPayload(payload, out);
        }
    }

    /**
     * 处理单个 data payload（已去掉 "data:" 前缀与可选空格后的值）。
     *
     * @return 是否为 [DONE]
     */
    private static boolean handleSingleDataPayload(String payload, List<Event> out) {
        String trimmed = payload.trim();
        if (trimmed.isEmpty()) {
            out.add(new Ignored("data: " + payload, "empty-data"));
            return false;
        }
        // 1) [DONE] 终止标记（允许前后空格，大小写敏感）
        if ("[DONE]".equals(trimmed)) {
            out.add(new Done(trimmed));
            return true;
        }
        // 2) 尝试 JSON 解析，非 JSON 容错为 Ignored
        JsonNode root;
        try {
            root = MAPPER.readTree(payload);
        } catch (Exception e) {
            // 对极长 payload，日志截断
            log.debug("SSE non-JSON ignored: {}", trimmed.length() > 200 ? trimmed.substring(0, 200) + "..." : trimmed);
            out.add(new Ignored(payload, "non-json"));
            return false;
        }
        // 3) 处理 JSON 结构
        // 特殊：网关在 [DONE] 后的额外 cost 块：{"choices":[],"cost":"0"}，无有效 delta，应忽略
        // 正常 chunk：{"choices":[{"delta":{...}, "finish_reason":...}], "usage":{...}}
        JsonNode choices = root.path("choices");
        // 若 root 本身是无 choices 的 cost/usage 裸对象
        if (!choices.isArray() || choices.isEmpty()) {
            // 顶层无 choices 的对象一律忽略（包含 cost 块、空 choices）
            // 若含 usage 但无 choices，也归为 ignored（usage 不外发）
            if (root.has("cost") || root.has("usage") || root.has("choices")) {
                out.add(new Ignored(payload, "no-choices"));
            } else {
                out.add(new Ignored(payload, "no-choices-unknown"));
            }
            return false;
        }
        JsonNode choice = choices.get(0);
        JsonNode delta = choice.path("delta");
        JsonNode finishReasonNode = choice.path("finish_reason");
        String finishReason = finishReasonNode.isNull() || finishReasonNode.isMissingNode() ? null : finishReasonNode.asText(null);
        boolean hasFinishReason = finishReason != null && !finishReason.isEmpty() && !"null".equals(finishReason);

        // 3.1 文本增量：delta.content 非空字符串
        boolean emitted = false;
        if (delta.isObject()) {
            JsonNode contentNode = delta.path("content");
            if (contentNode.isTextual()) {
                String text = contentNode.asText();
                if (text != null && !text.isEmpty()) {
                    out.add(new TextDelta(text));
                    emitted = true;
                } else if (text != null && text.isEmpty()) {
                    // 空 content 不发射，但若同时有 finishReason，可视作完成语义的空帧
                    // 不单独 emit Ignored，避免噪声
                }
            } else if (contentNode.isNull()) {
                // content: null 常见于工具调用首帧，忽略
            }
            // 3.2 工具调用增量：delta.tool_calls 数组
            JsonNode toolCalls = delta.path("tool_calls");
            if (toolCalls.isArray() && !toolCalls.isEmpty()) {
                for (JsonNode tc : toolCalls) {
                    int index = tc.path("index").asInt(0);
                    String id = tc.has("id") && !tc.path("id").isNull() ? tc.path("id").asText(null) : null;
                    JsonNode fn = tc.path("function");
                    String name = null;
                    String argsDelta = null;
                    if (fn.isObject()) {
                        if (fn.has("name") && !fn.path("name").isNull()) {
                            name = fn.path("name").asText(null);
                        }
                        if (fn.has("arguments") && !fn.path("arguments").isNull()) {
                            // arguments 可能是字符串（OpenAI 规范），也可能是对象（容错）
                            JsonNode argsNode = fn.path("arguments");
                            if (argsNode.isTextual()) {
                                argsDelta = argsNode.asText();
                            } else if (argsNode.isObject() || argsNode.isArray()) {
                                argsDelta = argsNode.toString();
                            } else {
                                argsDelta = argsNode.asText(null);
                            }
                        }
                    } else {
                        // 极端容错：tool_calls 元素直接含 arguments
                        if (tc.has("arguments")) {
                            argsDelta = tc.path("arguments").asText(null);
                        }
                    }
                    out.add(new ToolCallDelta(index, id, name, argsDelta));
                    emitted = true;
                }
            }
            // 3.3 reasoning 等字段：显式忽略，不发射也不记为 Ignored（保持安静）
        }

        // 3.4 finishReason 非空且未发射其他增量时，视为完成信号（补充于 [DONE] 之外）
        // 按 OpenAI 流，finish_reason 所在帧通常 content 为空，独立于 [DONE]；
        // 为保持 Done 语义单一，仅当未发射文本/工具增量且 hasFinishReason 时，追加 Done 事件
        if (hasFinishReason && !emitted) {
            // 若该帧同时含 usage，也忽略 usage
            out.add(new Done(finishReason));
            return false; // 非 [DONE] 的 finishReason 不触发 after-done 屏蔽，后续仍可有 usage 帧与 [DONE]
        }
        if (!emitted) {
            // 无文本、无工具、非 finish，纯 reasoning/空 delta 等，忽略
            // 为减少噪声，reasoning 帧不单独计为 Ignored，除非需要排查
            // 这里仍记录一条 Ignored 供穷举测试断言
            // 但为避免对 reasoning 大量噪声，可仅在无 finishReason 时记录
            if (!hasFinishReason) {
                out.add(new Ignored(payload, "no-delta"));
            }
        }
        return false;
    }
}
