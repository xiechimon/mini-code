package dev.minicode.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.minicode.ai.Message;

import java.time.Instant;

/**
 * 会话记录——append-only JSONL 树中的一条。对齐 pi 的 SessionEntry（type 判别器 + id/parentId 建树），
 * 见 {@code docs/wiki/21-hui-hua-jsonl-ge-shi-yu-sessionmanager.md}。
 * <p>
 * 有意简化：本轮只落 {@code session}（文件头）与 {@code message} 两种 type；pi 的 custom/custom_message/label/
 * thinking_level_change/model_change/bashExecution 等因 mini-code 暂无对应能力而留给未来（type 命名空间对齐，落地即加）。
 * 见 {@code docs/adr/0003}。
 * </p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class SessionEntry {

    public static final String TYPE_SESSION = "session";
    public static final String TYPE_MESSAGE = "message";
    public static final String TYPE_COMPACTION = "compaction";

    public String type;        // 判别器："session" | "message" | "compaction"
    public String id;          // 记录 id：头=会话 uuid；message=8-hex 短 id；compaction=8-hex 短 id
    public String parentId;    // 树父节点 id（根/头为 null）
    public String timestamp;   // ISO 8601

    /** message 条目载荷 */
    public Message message;

    /** compaction 条目载荷（见 {@code docs/adr/0004}）。summary 与 message 互斥使用。 */
    public String summary;
    public String firstKeptEntryId;
    public Integer tokensBefore;

    /** session 头扩展字段 */
    public Integer version;
    public String cwd;
    public String parentSession;

    public SessionEntry() {
    }

    /** 会话文件头条目。 */
    public static SessionEntry sessionHeader(String id, Integer version, String cwd, String parentSession) {
        SessionEntry e = new SessionEntry();
        e.type = TYPE_SESSION;
        e.id = id;
        e.version = version;
        e.cwd = cwd;
        e.parentSession = parentSession;
        e.timestamp = Instant.now().toString();
        return e;
    }

    /** 已完消息条目（含文本、工具调用与结果）。 */
    public static SessionEntry message(String id, String parentId, Message message) {
        SessionEntry e = new SessionEntry();
        e.type = TYPE_MESSAGE;
        e.id = id;
        e.parentId = parentId;
        e.message = message;
        e.timestamp = Instant.now().toString();
        return e;
    }

    /** 压缩条目（折已发旧历史为摘要，同时保留最末段原文）。firstKeptEntryId=保留段首个树的 id；tokensBefore=压缩前的总 token 估算。 */
    public static SessionEntry compaction(String id, String parentId, String summary,
                                         String firstKeptEntryId, int tokensBefore) {
        SessionEntry e = new SessionEntry();
        e.type = TYPE_COMPACTION;
        e.id = id;
        e.parentId = parentId;
        e.summary = summary;
        e.firstKeptEntryId = firstKeptEntryId;
        e.tokensBefore = tokensBefore;
        e.timestamp = Instant.now().toString();
        return e;
    }
}
