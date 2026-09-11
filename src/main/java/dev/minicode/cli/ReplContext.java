package dev.minicode.cli;

import dev.minicode.agent.AgentLoop;
import dev.minicode.agent.InterruptTrigger;
import dev.minicode.ai.LlmClient;
import dev.minicode.ai.Message;
import dev.minicode.ai.Model;
import dev.minicode.session.ContextCompactor;
import dev.minicode.session.SessionHistory;
import dev.minicode.session.SessionManager;
import dev.minicode.tools.ToolDefinition;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

/**
 * 对齐 {@code pi/pi-coding-agent/interactive-mode.ts}（InteractiveMode 的会话持有形状）：REPL 会话级状态容器与斜杠命令操作面。
 * <p>
 * 不可变依赖（构造注入，全程不换）：workdir / llm / tools / systemPrompt / triggerSupplier /
 * maxTurns / sessionsBaseDir / out / style / dispatcher。
 * 可变状态（volatile 引用）：loop / model / history / session / compactor —— 生命周期命令
 * （{@link #switchModel} {@link #newSession}）换入新实例，REPL 循环与压缩钩子须每次经
 * getter 取活引用，不得缓存。
 * </p>
 * <p>
 * 降级模式：session 为 null（会话初始化失败）时 compactor 亦为 null，命令须各自判空并给出
 * 不可用提示，不得抛异常打断 REPL。
 * </p>
 */
public final class ReplContext {

    private final Path workdir;
    private final LlmClient llm;
    private final List<ToolDefinition> tools;
    private final String systemPrompt;
    private final Supplier<InterruptTrigger> triggerSupplier;
    private final int maxTurns;
    private final Path sessionsBaseDir;
    private final PrintStream out;
    private final Style style;
    private final SlashDispatcher dispatcher;

    private volatile AgentLoop loop;
    private volatile Model model;
    private volatile List<Message> history;
    private volatile SessionManager session;
    private volatile ContextCompactor compactor;

    public ReplContext(Path workdir, LlmClient llm, List<ToolDefinition> tools, String systemPrompt,
                       Supplier<InterruptTrigger> triggerSupplier, int maxTurns, Path sessionsBaseDir,
                       PrintStream out, Style style,
                       AgentLoop loop, Model model, List<Message> history,
                       SessionManager session, ContextCompactor compactor, SlashDispatcher dispatcher) {
        this.workdir = workdir;
        this.llm = llm;
        this.tools = tools;
        this.systemPrompt = systemPrompt;
        this.triggerSupplier = triggerSupplier;
        this.maxTurns = maxTurns;
        this.sessionsBaseDir = sessionsBaseDir;
        this.out = out == null ? System.out : out;
        this.style = style;
        this.loop = loop;
        this.model = model;
        this.history = history;
        this.session = session;
        this.compactor = compactor;
        this.dispatcher = dispatcher;
    }

    // ===== 生命周期操作（/model /new /export 的操作面） =====

    /**
     * 切换模型（v1 限同 provider）：同 provider/baseUrl/api 重建 Model 并 new AgentLoop 换入。
     * {@code LlmClient.chat(model, ctx)} 每次传 model、与实例解耦，故切换无需动 AgentLoop 本体；
     * 跨 provider 涉及 apiKey 重解析，留后续（见 docs/adr/0006）。
     */
    public void switchModel(String newId) {
        Model cur = model;
        if (cur == null) {
            throw new IllegalStateException("当前无模型配置");
        }
        Model next = new Model(newId, cur.provider(), cur.baseUrl(), cur.api());
        this.model = next;
        this.loop = new AgentLoop(llm, next, systemPrompt, tools, maxTurns, triggerSupplier);
    }

    /**
     * 开新会话：关旧 SessionManager → 建新 → 换 SessionHistory 与 ContextCompactor。
     * 旧会话文件 append-only 留盘；降级模式（session 为 null）下视为重试建会话。
     */
    public void newSession() throws IOException {
        if (sessionsBaseDir == null) {
            throw new IOException("无会话根目录");
        }
        SessionManager old = session;
        if (old != null) {
            old.close();
        }
        SessionManager sm = SessionManager.create(sessionsBaseDir, workdir.toString());
        this.session = sm;
        this.history = new SessionHistory(sm);
        this.compactor = new ContextCompactor(sm);
    }

    /**
     * 导出当前会话 JSONL 到目标路径。SessionStore 每条 append 即 flush，复制无尾部丢失。
     * 目标已存在时抛 {@link java.nio.file.FileAlreadyExistsException}（不静默覆盖，由命令层提示换名）。
     *
     * @return 实际写入的路径
     */
    public Path exportSession(Path target) throws IOException {
        SessionManager sm = session;
        if (sm == null) {
            throw new IllegalStateException("无持久化会话");
        }
        Files.copy(sm.filePath(), target);
        return target;
    }

    // ===== 只读访问 =====

    public Path workdir() {
        return workdir;
    }

    public LlmClient llm() {
        return llm;
    }

    public List<ToolDefinition> tools() {
        return tools;
    }

    public String systemPrompt() {
        return systemPrompt;
    }

    public Supplier<InterruptTrigger> triggerSupplier() {
        return triggerSupplier;
    }

    /** 命令输出统一走这里（测试注入 StringWriter 背书的 PrintStream）。 */
    public PrintStream out() {
        return out;
    }

    public Style style() {
        return style;
    }

    public SlashDispatcher dispatcher() {
        return dispatcher;
    }

    public AgentLoop currentLoop() {
        return loop;
    }

    public Model model() {
        return model;
    }

    public List<Message> history() {
        return history;
    }

    /** 当前会话；降级模式为 null。 */
    public SessionManager session() {
        return session;
    }

    /** 当前压缩器；降级模式为 null。 */
    public ContextCompactor compactor() {
        return compactor;
    }
}
