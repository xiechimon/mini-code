package dev.minicode.session;

import dev.minicode.ai.Message;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;

/**
 * 会话历史视图——内存 history 的装饰器：{@link #addAll(Collection)} / {@link #add(Object)} 时同步把每条消息追加进会话 JSONL。
 * 对齐 pi：会话文件 append-only、永不删减；上层 cap-50 只裁剪内存上下文窗、不动会话文件。
 * 拦截范围：只覆盖 {@code addAll(Collection)} 与 {@code add(element)}；其余 List 变异方法（index 版 add/addAll、{@code set} 等）
 * 当前 REPL 不触发、未拦截，属有意简化（见 {@code docs/adr/0003}）。见 {@code docs/wiki/21-hui-hua-jsonl-ge-shi-yu-sessionmanager.md}。
 */
public class SessionHistory extends ArrayList<Message> {

    private final SessionManager session;

    public SessionHistory(SessionManager session) {
        if (session == null) throw new IllegalArgumentException("session 不可为 null");
        this.session = session;
    }

    @Override
    public boolean add(Message message) {
        if (message != null) {
            try {
                session.appendMessage(message);
            } catch (IOException e) {
                System.err.println("[mini-code] 会话写盘失败（已降级为内存 history）: " + e.getMessage());
            }
        }
        return super.add(message);
    }

    @Override
    public boolean addAll(Collection<? extends Message> c) {
        for (Message m : c) {
            if (m != null) {
                try {
                    session.appendMessage(m);
                } catch (IOException e) {
                    System.err.println("[mini-code] 会话写盘失败（已降级为内存 history）: " + e.getMessage());
                }
            }
        }
        return super.addAll(c);
    }

    /**
     * 仅覆盖内存视图的替换：用于压缩等场景，会话文件 append-only（commit 仅由 add/addAll 写入）。
     * 与 {@code clear()+addAll(...)} 的关键差别是不触发额外的会话写入。
     */
    public void replaceKeepingInMemory(Collection<? extends Message> c) {
        super.clear();
        if (c != null) super.addAll(c);
    }
}
