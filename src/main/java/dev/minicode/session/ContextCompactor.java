package dev.minicode.session;

import dev.minicode.ai.Context;
import dev.minicode.ai.LlmClient;
import dev.minicode.ai.Message;
import dev.minicode.ai.Model;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Context 压缩器：触发判定 + 单次 LLM 摘要调用 + 写 {@code compaction} 条目，构造压缩后的内存视图。
 * <p>
 * 摘要格式对且 pi 的 6 段检查点但简化为 4 段（Goal/Progress/Key Decisions/Next），见
 * {@code docs/adr/0004}。本类不掺入 {@link SessionManager}（纯写入）或
 * {@link dev.minicode.agent.AgentLoop}（调度）——压缩是“读-摘要-写-重建”专门逻辑。
 * </p>
 */
public final class ContextCompactor {

    private final SessionManager session;
    private final CompactionConfig.Resolved cfg;

    public ContextCompactor(SessionManager session) {
        this(session, CompactionConfig.resolve());
    }

    public ContextCompactor(SessionManager session, CompactionConfig.Resolved cfg) {
        if (session == null) throw new IllegalArgumentException("session 不可为 null");
        if (cfg == null) throw new IllegalArgumentException("cfg 不可为 null");
        this.session = session;
        this.cfg = cfg;
    }

    /** 触发判定：history 估算 token 超阈值。 */
    public boolean shouldCompact(List<Message> history) {
        int tokens = estimateTokensWithGuards(history, null);
        return tokens > Math.max(0, cfg.contextWindowTokens() - cfg.reserveTokens());
    }

    /** 带两条护栏的 token 估算：usage 优先（仅与 currentModel 同模型的条目采，否则视作丢弃），剩余走 chars/4。 */
    int estimateTokensWithGuards(List<Message> history, String currentModel) {
        if (history == null || history.isEmpty()) return 0;
        int n = 0;
        for (Message m : history) {
            // 跨模型护栏：usage 来源与当前 model 不一致则丢弃该 usage
            if (m.usage != null && m.usage.totalTokens != null
                    && (currentModel == null || currentModel.equals(m.modelId()))) {
                n += m.usage.totalTokens;
                continue;
            }
            n += CompactionConfig.estimateTokens(m.text());
        }
        return n;
    }

    /**
     * 压缩 history：调 LLM 生成摘要 → 写 {@code compaction} 条目（summary/firstKeptEntryId/tokensBefore）
     * → 返回压缩后视图（保留段原样）。失败降级为伪摘要，不中断调用方。
     *
     * @param currentModel 当前模型 id（用于跨模型护栏的 context，与 provider 一致）
     */
    public List<Message> compact(List<Message> history, LlmClient llm, Model model, String systemPrompt,
                                 String currentModel) throws IOException {
        if (history == null || history.isEmpty()) return List.of();
        int tokensBefore = estimateTokensWithGuards(history, currentModel);
        List<Message> keep = pickKeepRecent(history, cfg.keepRecentTokens());
        List<Message> toCompress = new ArrayList<>(history.subList(0, history.size() - keep.size()));
        if (toCompress.isEmpty()) return new ArrayList<>(keep);
        String summary;
        try {
            summary = summarize(toCompress, llm, model, systemPrompt);
        } catch (Throwable t) {
            summary = buildFallbackSummary(toCompress);
        }
        if (summary == null || summary.isBlank()) {
            summary = buildFallbackSummary(toCompress);
        }
        // firstKeptEntryId：保留段首条 Message.id（若第一条保留段没有 id，则不传，复投影走「压缩后新增」分支）
        String firstKeptId = keep.isEmpty() ? null : keep.get(0).id;
        session.appendCompaction(summary, firstKeptId, tokensBefore);
        return keep;
    }

    /** 从历史尾部起凑够 {@code keepRecentTokens} 个 token。 */
    static List<Message> pickKeepRecent(List<Message> history, int keepRecentTokens) {
        List<Message> rev = new ArrayList<>(history);
        Collections.reverse(rev);
        List<Message> picked = new ArrayList<>();
        int acc = 0;
        for (Message m : rev) {
            picked.add(m);
            acc += CompactionConfig.estimateTokens(m.text());
            if (acc >= keepRecentTokens) break;
        }
        Collections.reverse(picked);
        return picked;
    }

    /** 估算 history token 总量（每个 message.text() 的 chars/4 之和）。 */
    static int estimateTokens(List<Message> history) {
        if (history == null || history.isEmpty()) return 0;
        int n = 0;
        for (Message m : history) n += CompactionConfig.estimateTokens(m.text());
        return n;
    }

    /** 调 LLM 生成 4 段摘要；遇 {@code stopReason=length} 视为摘要截断、抛错让降级路径接手。 */
    private String summarize(List<Message> toCompress, LlmClient llm, Model model, String systemPrompt) throws Exception {
        if (model == null || llm == null) throw new IOException("摘要用 LLM 未配置");
        StringBuilder body = new StringBuilder();
        body.append("根据以下对话历史生成 4 段结构化摘要 (Goal/Progress/Key Decisions/Next)，如有旧摘要请增量合并：\n\n");
        for (Message m : toCompress) {
            body.append('[').append(m.role).append("] ").append(m.text()).append('\n');
        }
        String sp = (systemPrompt == null ? "" : systemPrompt + "\n\n") + body;
        Context ctx = new Context(sp, List.of(), List.of());
        Message resp = llm.chat(model, ctx);
        if (resp == null) throw new IOException("LLM 返回为空");
        if ("length".equals(resp.stopReason)) throw new IOException("LLM 摘要被截断 stopReason=length");
        return resp.text();
    }

    /** 失败降级伪摘要：保留首轮文本 + 条数，便于以后 LLM 能获点信息。 */
    static String buildFallbackSummary(List<Message> toCompress) {
        String first = (toCompress != null && !toCompress.isEmpty()) ? safeText(toCompress.get(0)) : "(无)";
        int n = toCompress == null ? 0 : toCompress.size();
        return "## Goal\n- " + first + "\n"
                + "## Progress\n- " + n + " 条历史已折叠\n"
                + "## Key Decisions\n- 见上下文\n"
                + "## Next\n- 继续当前任务\n";
    }

    private static String safeText(Message m) {
        if (m == null) return "(空)";
        String t = m.text();
        if (t == null || t.isBlank()) return "(空消息)";
        return t.length() > 200 ? t.substring(0, 200) + "..." : t;
    }
}
