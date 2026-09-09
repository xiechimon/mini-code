package dev.minicode.session;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 会话文件 IO——append-only JSONL。对齐 pi 的 SessionManager 日志存储（见 {@code docs/wiki/21-hui-hua-jsonl-ge-shi-yu-sessionmanager.md}）。
 * 单进程单写者，每消息 append 一次、无锁（pi 的 proper-lockfile 只为 auth.json 跨进程写）。
 * 加载健壮性镜像 pi：跳过空白/畸形行；文件首条需为 {@code session} 头，否则判为非法会话文件。
 */
public final class SessionStore implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path path;
    private final BufferedWriter writer;

    SessionStore(Path path) throws IOException {
        this.path = path;
        Path parent = path.getParent();
        if (parent != null) Files.createDirectories(parent);
        this.writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** 追加一条记录（同步写 + flush，保证每条落盘）。 */
    public synchronized void append(SessionEntry entry) throws IOException {
        writer.write(MAPPER.writeValueAsString(entry));
        writer.newLine();
        writer.flush();
    }

    @Override
    public void close() throws IOException {
        writer.close();
    }

    /**
     * 载入整文件，返回记录的物理行序（首条为 session 头）。
     * 空文件或不存在返回空列表；畸形行跳过；文件首条非 session 头抛 IOException。
     */
    public static List<SessionEntry> load(Path path) throws IOException {
        if (!Files.exists(path)) return List.of();
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        List<SessionEntry> entries = new ArrayList<>();
        boolean firstValidLine = true;
        for (String line : lines) {
            if (line == null || line.isBlank()) continue;
            SessionEntry e;
            try {
                e = MAPPER.readValue(line, SessionEntry.class);
            } catch (Exception parseEx) {
                continue; // 畸形行：跳过（镜像 pi 容错）
            }
            if (firstValidLine) {
                if (!SessionEntry.TYPE_SESSION.equals(e.type)) {
                    throw new IOException("会话文件首行非 session 头: " + path);
                }
                firstValidLine = false;
            }
            entries.add(e);
        }
        return entries;
    }
}
