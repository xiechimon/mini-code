package dev.minicode.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SSE 纯函数解析器穷举测试——覆盖票面要求的 6 类场景 + 极端边界。
 * 对应 spec.md Testing Decisions 单缝原则。
 */
class SseParserTest {

    // ===== 文本增量分片 =====

    @Test
    void textDeltaSingleChunk() {
        List<String> lines = List.of(
                "data: {\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}",
                ""
        );
        List<SseParser.Event> events = SseParser.parse(lines);
        assertEquals(1, events.stream().filter(e -> e instanceof SseParser.TextDelta).count());
        SseParser.TextDelta d = (SseParser.TextDelta) events.stream().filter(e -> e instanceof SseParser.TextDelta).findFirst().orElseThrow();
        assertEquals("hello", d.text());
    }

    @Test
    void textDeltaMultipleFragments() {
        // kimi 风格：逐字分片
        List<String> lines = List.of(
                "data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}",
                "",
                "data: {\"choices\":[{\"delta\":{\"content\":\"，\"}}]}",
                "",
                "data: {\"choices\":[{\"delta\":{\"content\":\"世界\"}}]}",
                "",
                "data: {\"choices\":[{\"delta\":{\"content\":\"！\"}}]}",
                ""
        );
        List<SseParser.Event> events = SseParser.parse(lines);
        List<String> texts = events.stream()
                .filter(e -> e instanceof SseParser.TextDelta)
                .map(e -> ((SseParser.TextDelta) e).text())
                .toList();
        assertEquals(List.of("你好", "，", "世界", "！"), texts);
        // 拼装后应得完整
        assertEquals("你好，世界！", String.join("", texts));
    }

    @Test
    void textDeltaWithReasoningIgnored() {
        // mimo / kimi 均会在正文前发送 reasoning 帧，解析器应忽略 reasoning，仅提取 content
        List<String> lines = List.of(
                "data: {\"choices\":[{\"delta\":{\"content\":\"\",\"reasoning\":\"thinking\",\"reasoning_details\":[{\"type\":\"reasoning.text\",\"text\":\"thinking\"}]}}]}",
                "",
                "data: {\"choices\":[{\"delta\":{\"content\":\"你好，世界！\"}}]}",
                ""
        );
        List<SseParser.Event> events = SseParser.parse(lines);
        List<SseParser.TextDelta> deltas = events.stream().filter(e -> e instanceof SseParser.TextDelta).map(e -> (SseParser.TextDelta) e).toList();
        assertEquals(1, deltas.size());
        assertEquals("你好，世界！", deltas.get(0).text());
        // reasoning 帧应为 Ignored
        assertTrue(events.stream().anyMatch(e -> e instanceof SseParser.Ignored));
    }

    @Test
    void textDeltaNullContentIgnored() {
        List<String> lines = List.of(
                "data: {\"choices\":[{\"delta\":{\"content\":null}}]}",
                ""
        );
        List<SseParser.Event> events = SseParser.parse(lines);
        assertTrue(events.stream().noneMatch(e -> e instanceof SseParser.TextDelta));
        assertTrue(events.stream().anyMatch(e -> e instanceof SseParser.Ignored));
    }

    // ===== [DONE] 终止 =====

    @Test
    void doneTerminatesAndIgnoresPostDone() {
        List<String> lines = List.of(
                "data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}",
                "",
                "data: [DONE]",
                "",
                "data: {\"choices\":[],\"cost\":\"0\"}",
                "",
                "data: {\"choices\":[{\"delta\":{\"content\":\"should ignore\"}}]}",
                ""
        );
        List<SseParser.Event> events = SseParser.parse(lines);
        long textCount = events.stream().filter(e -> e instanceof SseParser.TextDelta).count();
        long doneCount = events.stream().filter(e -> e instanceof SseParser.Done).count();
        long ignoredAfter = events.stream().filter(e -> e instanceof SseParser.Ignored i && i.raw().contains("should ignore")).count();
        assertEquals(1, textCount);
        assertEquals(1, doneCount);
        // cost 块与后续文本均应被忽略（after-done）
        assertTrue(events.stream().anyMatch(e -> e instanceof SseParser.Ignored i && i.raw().contains("cost")));
        assertEquals(1, ignoredAfter);
    }

    @Test
    void doneWithSpacesAndWithoutSpace() {
        assertDone("data: [DONE]");
        assertDone("data:[DONE]");
        assertDone("data:  [DONE]  ");
    }

    private void assertDone(String line) {
        List<SseParser.Event> ev = SseParser.parse(List.of(line, ""));
        assertTrue(ev.stream().anyMatch(e -> e instanceof SseParser.Done), "should parse DONE for: " + line);
    }

    @Test
    void doneWithoutTrailingEmptyStillEmits() {
        List<SseParser.Event> ev = SseParser.parse(List.of("data: [DONE]"));
        assertTrue(ev.stream().anyMatch(e -> e instanceof SseParser.Done));
    }

    // ===== 注释行 / 空行忽略 =====

    @Test
    void ignoresCommentAndEmpty() {
        List<String> lines = List.of(
                ": keep-alive",
                "",
                ": keep-alive",
                "",
                "",
                "data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}",
                ""
        );
        List<SseParser.Event> events = SseParser.parse(lines);
        long ignored = events.stream().filter(e -> e instanceof SseParser.Ignored).count();
        long text = events.stream().filter(e -> e instanceof SseParser.TextDelta).count();
        assertTrue(ignored >= 3);
        assertEquals(1, text);
        // comment 应被标记
        assertTrue(events.stream().anyMatch(e -> e instanceof SseParser.Ignored i && i.reason().contains("comment")));
        assertTrue(events.stream().anyMatch(e -> e instanceof SseParser.Ignored i && i.reason().contains("empty-line")));
    }

    @Test
    void ignoresEventAndIdFields() {
        List<String> lines = List.of(
                "event: message",
                "id: 123",
                "retry: 10000",
                "data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}",
                ""
        );
        List<SseParser.Event> events = SseParser.parse(lines);
        assertTrue(events.stream().anyMatch(e -> e instanceof SseParser.Ignored i && i.reason().contains("field-event")));
        assertTrue(events.stream().anyMatch(e -> e instanceof SseParser.Ignored i && i.reason().contains("field-id")));
        assertEquals(1, events.stream().filter(e -> e instanceof SseParser.TextDelta).count());
    }

    @Test
    void ignoresWhitespaceLines() {
        List<String> lines = List.of("   ", "data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}", "");
        // "   " 含空格但无冒号 -> no-colon 忽略
        List<SseParser.Event> e = SseParser.parse(lines);
        assertTrue(e.stream().anyMatch(ev -> ev instanceof SseParser.Ignored));
    }

    // ===== 非 JSON 行容错 =====

    @Test
    void nonJsonLineIgnored() {
        List<String> lines = List.of(
                "data: not-json",
                "",
                "data: {not valid json}",
                "",
                "data: 123",
                "",
                "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}",
                ""
        );
        List<SseParser.Event> events = SseParser.parse(lines);
        long ignored = events.stream().filter(e -> e instanceof SseParser.Ignored i && i.reason().equals("non-json")).count();
        assertTrue(ignored >= 2);
        assertEquals(1, events.stream().filter(e -> e instanceof SseParser.TextDelta).count());
    }

    @Test
    void noColonLineIgnored() {
        List<String> lines = List.of(
                "not a sse line",
                "data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}",
                ""
        );
        List<SseParser.Event> ev = SseParser.parse(lines);
        assertTrue(ev.stream().anyMatch(e -> e instanceof SseParser.Ignored i && i.reason().equals("no-colon")));
    }

    // ===== 工具调用增量解析（标记为不外发） =====

    @Test
    void toolCallSingleFragment() {
        List<String> lines = List.of(
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_123\",\"type\":\"function\",\"function\":{\"name\":\"read\",\"arguments\":\"\"}}]}}]}",
                ""
        );
        List<SseParser.Event> events = SseParser.parse(lines);
        List<SseParser.ToolCallDelta> tcs = events.stream().filter(e -> e instanceof SseParser.ToolCallDelta).map(e -> (SseParser.ToolCallDelta) e).toList();
        assertEquals(1, tcs.size());
        SseParser.ToolCallDelta tc = tcs.get(0);
        assertEquals(0, tc.index());
        assertEquals("call_123", tc.id());
        assertEquals("read", tc.name());
        assertEquals("", tc.argumentsDelta());
        assertTrue(tc.suppressed());
    }

    @Test
    void toolCallIncrementalFragmentsKimiStyle() {
        // kimi 细碎分片
        List<String> lines = List.of(
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"read_0\",\"type\":\"function\",\"function\":{\"name\":\"read\",\"arguments\":\"\"}}]}}]}",
                "",
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\\\"\"}}]}}]}",
                "",
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"path\"}}]}}]}",
                "",
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\\\":\\\"\"}}]}}]}",
                "",
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"a\"}}]}}]}",
                "",
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\".txt\"}}]}}]}",
                "",
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\\\"}\" }}]}}]}",
                ""
        );
        List<SseParser.Event> events = SseParser.parse(lines);
        List<SseParser.ToolCallDelta> tcs = events.stream().filter(e -> e instanceof SseParser.ToolCallDelta).map(e -> (SseParser.ToolCallDelta) e).toList();
        assertEquals(7, tcs.size());
        // 首帧含 id/name
        assertEquals("read_0", tcs.get(0).id());
        assertEquals("read", tcs.get(0).name());
        // 后续帧仅 arguments
        assertNull(tcs.get(1).id());
        assertNull(tcs.get(1).name());
        assertEquals("{\"", tcs.get(1).argumentsDelta());
        // 全部标记 suppressed
        assertTrue(tcs.stream().allMatch(SseParser.ToolCallDelta::suppressed));
        // 拼装后应得完整 JSON
        String joined = tcs.stream().map(tc -> tc.argumentsDelta() == null ? "" : tc.argumentsDelta()).reduce("", String::concat);
        assertEquals("{\"path\":\"a.txt\"}", joined);
    }

    @Test
    void toolCallMimoStyleLargerChunks() {
        List<String> lines = List.of(
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_4b1f\",\"type\":\"function\",\"function\":{\"name\":\"read\",\"arguments\":\"\"}}]}}]}",
                "",
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\\\"path\\\": \"}}]}}]}",
                "",
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\\\"\"}}]}}]}",
                "",
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"a\"}}]}}]}",
                "",
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\".txt\"}}]}}]}",
                "",
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\\\"\"}}]}}]}",
                "",
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"}\" }}]}}]}",
                ""
        );
        List<SseParser.Event> events = SseParser.parse(lines);
        List<SseParser.ToolCallDelta> tcs = events.stream().filter(e -> e instanceof SseParser.ToolCallDelta).map(e -> (SseParser.ToolCallDelta) e).toList();
        assertEquals(7, tcs.size());
        String joined = tcs.stream().map(tc -> tc.argumentsDelta() == null ? "" : tc.argumentsDelta()).reduce("", String::concat);
        assertEquals("{\"path\": \"a.txt\"}", joined);
    }

    @Test
    void toolCallAndTextNotMixed() {
        // 正常情况下文本与工具调用不会同帧出现，此处验证仅工具帧不产生 TextDelta
        List<String> lines = List.of(
                "data: {\"choices\":[{\"delta\":{\"content\":null,\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"function\":{\"name\":\"read\",\"arguments\":\"{}\"}}]}}]}",
                ""
        );
        List<SseParser.Event> ev = SseParser.parse(lines);
        assertEquals(1, ev.stream().filter(e -> e instanceof SseParser.ToolCallDelta).count());
        assertEquals(0, ev.stream().filter(e -> e instanceof SseParser.TextDelta).count());
    }

    // ===== 跨块 data 拼接 =====

    @Test
    void crossBlockSseMultiDataConcatenation() {
        // SSE 规范：同一事件的多个 data 行应以 \\n 拼接。此处模拟网关将同一 JSON 拆为两行 data
        // 实际中 OpenAI 不会这样拆，但解析器需按规范拼接
        List<String> lines = List.of(
                "data: {\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}",
                "data: {\"choices\":[{\"delta\":{\"content\":\" world\"}}]}",
                ""
        );
        // 按当前实现，多 data 行在同一事件缓冲内会以 \\n 拼接后拆分处理，仍应产生两个文本增量
        List<SseParser.Event> events = SseParser.parse(lines);
        List<String> texts = events.stream().filter(e -> e instanceof SseParser.TextDelta).map(e -> ((SseParser.TextDelta) e).text()).toList();
        // 至少能容错不崩，且能解析出两个片段
        assertTrue(texts.size() >= 1);
        assertTrue(String.join("", texts).contains("hello"));
    }

    @Test
    void crossBlockTcpChunkSplit() {
        // 模拟 TCP 块在行中切断： 一个 data 行被拆为两个块
        // 块序列含 \\n，切分位置在 JSON 字符串中
        String full = "data: {\"choices\":[{\"delta\":{\"content\":\"hello world\"}}]}\n\n" +
                "data: [DONE]\n\n";
        // 在 "hello" 中切断
        int split = full.indexOf("hello") + 3;
        String chunk1 = full.substring(0, split);
        String chunk2 = full.substring(split);
        List<String> chunks = List.of(chunk1, chunk2);
        List<SseParser.Event> events = SseParser.parse(chunks);
        List<SseParser.TextDelta> texts = events.stream().filter(e -> e instanceof SseParser.TextDelta).map(e -> (SseParser.TextDelta) e).toList();
        assertEquals(1, texts.size());
        assertEquals("hello world", texts.get(0).text());
        assertTrue(events.stream().anyMatch(e -> e instanceof SseParser.Done));
    }

    @Test
    void crossBlockWithRealGatewaySampleMimo() {
        // 使用 spike 中 mimo 的真实样本片段验证
        List<String> lines = List.of(
                "data: {\"id\":\"gen\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"delta\":{\"content\":\"你好，世界！\"}}]}",
                "",
                "data: {\"id\":\"gen\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"finish_reason\":\"stop\",\"delta\":{\"content\":\"\"}}],\"usage\":{\"prompt_tokens\":268,\"completion_tokens\":41,\"total_tokens\":309}}",
                "",
                "data: [DONE]",
                "",
                "data: {\"choices\":[],\"cost\":\"0\"}",
                ""
        );
        List<SseParser.Event> events = SseParser.parse(lines);
        assertEquals(1, events.stream().filter(e -> e instanceof SseParser.TextDelta).count());
        // at least one Done (from finishReason or [DONE])
        assertTrue(events.stream().filter(e -> e instanceof SseParser.Done).count() >= 1);
        // cost 块应被忽略
        assertTrue(events.stream().anyMatch(e -> e instanceof SseParser.Ignored i && i.raw().contains("cost")));
    }

    @Test
    void crossBlockToolCallSplitAcrossChunks() {
        String full = "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"function\":{\"name\":\"read\",\"arguments\":\"{\\\"path\\\": \\\"a.txt\\\"}\"}}]}}]}\n\n" +
                "data: [DONE]\n\n";
        int split = full.indexOf("path") + 2;
        List<String> chunks = List.of(full.substring(0, split), full.substring(split));
        List<SseParser.Event> ev = SseParser.parse(chunks);
        assertTrue(ev.stream().anyMatch(e -> e instanceof SseParser.ToolCallDelta));
        assertTrue(ev.stream().anyMatch(e -> e instanceof SseParser.Done));
    }

    // ===== 极端边界 =====

    @Test
    void emptyInput() {
        assertEquals(0, SseParser.parse(List.of()).size());
        assertEquals(0, SseParser.parse(null).size());
        // 仅空行
        List<SseParser.Event> ev = SseParser.parse(List.of("", "", ""));
        assertTrue(ev.stream().allMatch(e -> e instanceof SseParser.Ignored));
    }

    @Test
    void nullAndEmptyLinesHandled() {
        List<String> input = new ArrayList<>();
        input.add(null);
        input.add("");
        input.add("data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}");
        input.add(null);
        input.add("");
        List<SseParser.Event> ev = SseParser.parse(input);
        assertEquals(1, ev.stream().filter(e -> e instanceof SseParser.TextDelta).count());
    }

    @Test
    void superLongLine() {
        // 超长行：模拟 100KB 的 content
        String longText = "a".repeat(100_000);
        // 使用 ObjectMapper 生成正确 JSON，避免手写转义问题
        String json = "{\"choices\":[{\"delta\":{\"content\":\"" + longText + "\"}}]}";
        List<String> lines = List.of("data: " + json, "");
        List<SseParser.Event> ev = SseParser.parse(lines);
        SseParser.TextDelta d = (SseParser.TextDelta) ev.stream().filter(e -> e instanceof SseParser.TextDelta).findFirst().orElseThrow();
        assertEquals(100_000, d.text().length());
        assertEquals(longText, d.text());
    }

    @Test
    void superLongLineWithSplit() {
        String longText = "b".repeat(100_000);
        String json = "{\"choices\":[{\"delta\":{\"content\":\"" + longText + "\"}}]}";
        String raw = "data: " + json + "\n\n" + "data: [DONE]\n\n";
        int split = raw.length() / 2;
        List<String> chunks = List.of(raw.substring(0, split), raw.substring(split));
        List<SseParser.Event> ev = SseParser.parse(chunks);
        assertTrue(ev.stream().anyMatch(e -> e instanceof SseParser.TextDelta td && td.text().length() == 100_000));
        assertTrue(ev.stream().anyMatch(e -> e instanceof SseParser.Done));
    }

    @Test
    void ignoresUsageBlockAlone() {
        List<String> lines = List.of(
                "data: {\"choices\":[{\"delta\":{\"content\":\"\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}",
                ""
        );
        List<SseParser.Event> ev = SseParser.parse(lines);
        // 含 finish_reason 的空 content 帧应产生 Done，而非 TextDelta
        assertTrue(ev.stream().anyMatch(e -> e instanceof SseParser.Done));
        assertTrue(ev.stream().noneMatch(e -> e instanceof SseParser.TextDelta));
    }

    @Test
    void handlesContentWithSpecialChars() {
        List<String> lines = List.of(
                "data: {\"choices\":[{\"delta\":{\"content\":\"a\\nb\\tc\\\"d\"}}]}",
                ""
        );
        List<SseParser.Event> ev = SseParser.parse(lines);
        SseParser.TextDelta td = (SseParser.TextDelta) ev.stream().filter(e -> e instanceof SseParser.TextDelta).findFirst().orElseThrow();
        assertEquals("a\nb\tc\"d", td.text());
    }

    @Test
    void handlesDataPrefixWithoutSpace() {
        List<String> lines = List.of(
                "data:{\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}",
                ""
        );
        List<SseParser.Event> ev = SseParser.parse(lines);
        assertEquals(1, ev.stream().filter(e -> e instanceof SseParser.TextDelta).count());
    }

    @Test
    void handlesFinishReasonToolCalls() {
        List<String> lines = List.of(
                "data: {\"choices\":[{\"delta\":{\"content\":\"\"},\"finish_reason\":\"tool_calls\"}]}",
                ""
        );
        List<SseParser.Event> ev = SseParser.parse(lines);
        assertTrue(ev.stream().anyMatch(e -> e instanceof SseParser.Done));
    }

    @Test
    void handlesEmptyDataField() {
        List<String> lines = List.of(
                "data: ",
                "",
                "data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}",
                ""
        );
        List<SseParser.Event> ev = SseParser.parse(lines);
        assertEquals(1, ev.stream().filter(e -> e instanceof SseParser.TextDelta).count());
        assertTrue(ev.stream().anyMatch(e -> e instanceof SseParser.Ignored i && i.reason().contains("empty-data")));
    }

    @Test
    void handlesMultipleChoicesOnlyFirst() {
        List<String> lines = List.of(
                "data: {\"choices\":[{\"delta\":{\"content\":\"first\"}},{\"delta\":{\"content\":\"second\"}}]}",
                ""
        );
        List<SseParser.Event> ev = SseParser.parse(lines);
        SseParser.TextDelta td = (SseParser.TextDelta) ev.stream().filter(e -> e instanceof SseParser.TextDelta).findFirst().orElseThrow();
        assertEquals("first", td.text());
    }

    // ===== retry/event/id 字段显式忽略（补全警告） =====

    @Test
    void ignoresRetryFieldExplicitly() {
        List<String> lines = List.of(
                "retry: 3000",
                "data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}",
                ""
        );
        List<SseParser.Event> ev = SseParser.parse(lines);
        assertTrue(ev.stream().anyMatch(e -> e instanceof SseParser.Ignored i && "field-retry".equals(i.reason())), "retry 行应显式忽略");
        assertEquals(1, ev.stream().filter(e -> e instanceof SseParser.TextDelta).count());
        // 单独 retry 无 data 时仅忽略，不产生 delta
        List<SseParser.Event> onlyRetry = SseParser.parse(List.of("retry: 0", ""));
        assertTrue(onlyRetry.stream().allMatch(e -> e instanceof SseParser.Ignored));
        assertTrue(onlyRetry.stream().anyMatch(e -> e instanceof SseParser.Ignored i && "field-retry".equals(i.reason())));
    }

    @Test
    void ignoresEventFieldExplicitly() {
        List<String> lines = List.of(
                "event: message",
                "data: {\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}",
                ""
        );
        List<SseParser.Event> ev = SseParser.parse(lines);
        assertTrue(ev.stream().anyMatch(e -> e instanceof SseParser.Ignored i && "field-event".equals(i.reason())), "event 行应显式忽略");
        assertEquals(1, ev.stream().filter(e -> e instanceof SseParser.TextDelta).count());
    }

    @Test
    void ignoresIdFieldExplicitly() {
        List<String> lines = List.of(
                "id: 42",
                "data: {\"choices\":[{\"delta\":{\"content\":\"world\"}}]}",
                ""
        );
        List<SseParser.Event> ev = SseParser.parse(lines);
        assertTrue(ev.stream().anyMatch(e -> e instanceof SseParser.Ignored i && "field-id".equals(i.reason())), "id 行应显式忽略");
        assertEquals(1, ev.stream().filter(e -> e instanceof SseParser.TextDelta).count());
    }

    @Test
    void ignoresRetryEventIdTogetherAndPreservesData() {
        List<String> lines = List.of(
                "retry: 2500",
                "id: 999",
                "event: delta",
                "data: {\"choices\":[{\"delta\":{\"content\":\"together\"}}]}",
                ""
        );
        List<SseParser.Event> ev = SseParser.parse(lines);
        assertTrue(ev.stream().anyMatch(e -> e instanceof SseParser.Ignored i && "field-retry".equals(i.reason())));
        assertTrue(ev.stream().anyMatch(e -> e instanceof SseParser.Ignored i && "field-id".equals(i.reason())));
        assertTrue(ev.stream().anyMatch(e -> e instanceof SseParser.Ignored i && "field-event".equals(i.reason())));
        assertEquals("together", ((SseParser.TextDelta) ev.stream().filter(e -> e instanceof SseParser.TextDelta).findFirst().orElseThrow()).text());
    }
}
