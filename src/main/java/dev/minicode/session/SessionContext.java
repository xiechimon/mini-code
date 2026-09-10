package dev.minicode.session;

import dev.minicode.ai.Message;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话上下文投影——把 JSONL 树按 leaf 重建为「当前上下文视图」。
 * <p>
 * 规则（对齐 pi）：
 * <ol>
 *   <li>按 {@code parentId} 由 leaf 回溯至根，再反转得到叶子到根的链条；</li>
 *   <li>在链上找**最后一个** {@code compaction} 节点，作为拆缝点；</li>
 *   <li>输出 = [summary（<code>&lt;summary&gt;...&lt;/summary&gt;</code> 系统消息）] + [{@code firstKeptEntryId} 到压缩点之间的保留段] + [压缩点之后的新增条目]。</li>
 * </ol>
 * 压缩点之前的「被折常条目」不进入上下文，但完整保留在 JSONL 文件中（append-only）。
 * 见 {@code docs/adr/0004}。
 * </p>
 */
public final class SessionContext {

    private SessionContext() {
    }

    /** 加载 JSONL 文件并在内存重建上下文视图。 */
    public static List<Message> load(Path file) throws IOException {
        return loadFromEntries(SessionStore.load(file));
    }

    /** 从已读入的记录重建视图（供测试/嵌入使用）。 */
    public static List<Message> loadFromEntries(List<SessionEntry> entries) {
        if (entries.isEmpty()) return List.of();
        Map<String, SessionEntry> byId = new HashMap<>();
        for (SessionEntry e : entries) {
            if (e.id != null) byId.put(e.id, e);
        }
        // 由 leaf（最后一条）回溯到根
        List<SessionEntry> pathToRoot = new ArrayList<>();
        String leafId = entries.get(entries.size() - 1).id;
        String cur = leafId;
        int safety = 0;
        while (cur != null && byId.containsKey(cur) && safety++ < entries.size() + 2) {
            SessionEntry e = byId.get(cur);
            pathToRoot.add(e);
            cur = e.parentId;
        }
        Collections.reverse(pathToRoot); // 根 → leaf
        return project(pathToRoot);
    }

    /** 从根到叶的路径上投影：[summary] + [保留段] + [压缩点之后新增]。 */
    static List<Message> project(List<SessionEntry> rootToLeaf) {
        if (rootToLeaf.isEmpty()) return List.of();

        int start = 0;
        while (start < rootToLeaf.size() && SessionEntry.TYPE_SESSION.equals(rootToLeaf.get(start).type)) {
            start++;
        }
        if (start >= rootToLeaf.size()) return List.of();

        // 链上最后一个 compaction
        int compactionIdx = -1;
        for (int i = rootToLeaf.size() - 1; i >= start; i--) {
            if (SessionEntry.TYPE_COMPACTION.equals(rootToLeaf.get(i).type)) {
                compactionIdx = i;
                break;
            }
        }

        List<Message> out = new ArrayList<>();
        if (compactionIdx < 0) {
            for (int i = start; i < rootToLeaf.size(); i++) {
                SessionEntry e = rootToLeaf.get(i);
                if (SessionEntry.TYPE_MESSAGE.equals(e.type) && e.message != null) {
                    out.add(e.message);
                }
            }
            return out;
        }

        SessionEntry compaction = rootToLeaf.get(compactionIdx);
        // 1) summary 消息打头
        if (compaction.summary != null) {
            out.add(Message.system("<summary>\n" + compaction.summary + "\n</summary>"));
        }

        // 2) 保留段：[firstKeptEntryId..compressionPos-1]
        if (compaction.firstKeptEntryId != null) {
            for (int i = start; i < compactionIdx; i++) {
                if (compaction.firstKeptEntryId.equals(rootToLeaf.get(i).id)
                        && SessionEntry.TYPE_MESSAGE.equals(rootToLeaf.get(i).type)
                        && rootToLeaf.get(i).message != null) {
                    for (int j = i; j < compactionIdx; j++) {
                        SessionEntry m = rootToLeaf.get(j);
                        if (SessionEntry.TYPE_MESSAGE.equals(m.type) && m.message != null) {
                            out.add(m.message);
                        }
                    }
                    break;
                }
            }
        }

        // 3) 压缩点之后的新增
        for (int i = compactionIdx + 1; i < rootToLeaf.size(); i++) {
            SessionEntry e = rootToLeaf.get(i);
            if (SessionEntry.TYPE_MESSAGE.equals(e.type) && e.message != null) {
                out.add(e.message);
            }
        }
        return out;
    }
}
