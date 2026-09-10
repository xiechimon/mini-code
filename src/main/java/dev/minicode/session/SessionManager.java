package dev.minicode.session;

import dev.minicode.ai.Message;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 会话管理——append-only JSONL 树的树/leaf 状态。对齐 pi 的 SessionManager（写头、追加、leaf 指针），
 * 见 {@code docs/wiki/21-hui-hua-jsonl-ge-shi-yu-sessionmanager.md}。
 * <p>
 * 本轮只做：新建会话（写头）+ 每条已完消息 append（id/parentId 建链、推进 leaf）。读-重建路径
 * （<code>open</code>/<code>loadContext</code> 把树重建为 {@code List<Message>}）留给 resume 迭代；
 * 写入侧的结构从第一天就搭好（docs/adr/0003）。
 * </p>
 */
public final class SessionManager implements AutoCloseable {

    /** 会话文件 schema 版本。 */
    public static final int VERSION = 1;

    private final Path filePath;
    private final SessionStore store;
    private final String sessionId;
    private String leafId; // 树当前叶子记录 id；追加以它为父

    private SessionManager(Path filePath, SessionStore store, String sessionId) {
        this.filePath = filePath;
        this.store = store;
        this.sessionId = sessionId;
        this.leafId = sessionId; // 根链：头节点 id 起
    }

    /** 新建会话：在 base/sessions/--<cwd 编码>--/ 下建文件并写会话头。 */
    public static SessionManager create(Path baseDir, String cwd) throws IOException {
        String id = UUID.randomUUID().toString();
        Path file = sessionFile(baseDir, cwd, id);
        SessionManager m = new SessionManager(file, new SessionStore(file), id);
        m.store.append(SessionEntry.sessionHeader(id, VERSION, cwd, null));
        return m;
    }

    /** 追加一条已完消息为 message 条目（以当前 leaf 为父，追加后推进 leaf）。 */
    public void appendMessage(Message message) throws IOException {
        String id = newShortId();
        store.append(SessionEntry.message(id, leafId, message));
        leafId = id;
    }

    /**
     * 追加一条 compaction 条目（以当前 leaf 为父，追加后推进 leaf）。
     * 见 {@code docs/adr/0004}。{@code firstKeptEntryId} 本迭代置 null，对齐保留段以「压缩点后新增」读入式提取，
     * 未来若需要 message-level id 字段（让保留段从头几倍增加仍现于上下文），需给 {@link Message} 加 id。
     */
    public void appendCompaction(String summary, String firstKeptEntryId, int tokensBefore) throws IOException {
        String id = newShortId();
        store.append(SessionEntry.compaction(id, leafId, summary, firstKeptEntryId, tokensBefore));
        leafId = id;
    }

    public Path filePath() {
        return filePath;
    }

    @Override
    public void close() throws IOException {
        store.close();
    }

    /** 会话文件路径：base/sessions/--<cwd 编码>--/<时间戳>_<uuid>.jsonl（对齐 pi 的 cwd 分桶 + 时间戳文件名）。 */
    static Path sessionFile(Path baseDir, String cwd, String id) throws IOException {
        Path bucket = baseDir.resolve("sessions").resolve(bucketName(cwd));
        Files.createDirectories(bucket);
        String ts = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(ZoneId.systemDefault()).format(Instant.now());
        return bucket.resolve(ts + "_" + id + ".jsonl");
    }

    /** cwd → --<去掉前导 /，把 / \ : 换成 ->（对齐 pi 的分桶规则）。 */
    static String bucketName(String cwd) {
        String s = cwd;
        if (s.startsWith("/")) s = s.substring(1);
        s = s.replaceAll("[/\\\\:]", "-");
        return "--" + s + "--";
    }

    /** 8-hex 短 id（对齐 pi 短 ID）：对照当前 leaf/会话根做碰撞重试，100 次超限回退 UUID。 */
    private String newShortId() {
        for (int i = 0; i < 100; i++) {
            String id = String.format("%08x", new SecureRandom().nextInt());
            if (!id.equals(leafId) && !id.equals(sessionId)) {
                return id;
            }
        }
        return UUID.randomUUID().toString();
    }
}
