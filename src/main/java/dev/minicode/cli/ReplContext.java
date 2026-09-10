package dev.minicode.cli;

import dev.minicode.agent.AgentLoop;
import dev.minicode.agent.InterruptTrigger;
import dev.minicode.ai.LlmClient;
import dev.minicode.ai.Message;
import dev.minicode.ai.Model;
import dev.minicode.session.ContextCompactor;
import dev.minicode.session.SessionManager;
import dev.minicode.tools.ToolDefinition;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

/**
 * REPL 会话级状态容器与斜杠命令操作面（pi InteractiveMode 会话持有的前兆形状）。
 * <p>
 * 不可变依赖（构造注入，全程不换）：workdir / llm / tools / systemPrompt / triggerSupplier / out / style / dispatcher。
 * 可变状态（volatile 引用）：loop / model / history / session / compactor —— 票 01 仅构造期赋值，
 * 换入操作（switchModel/newSession/exportSession）随票 02 落地；字段先全量注入，避免 02 再动构造缝。
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
    private final PrintStream out;
    private final Style style;
    private final SlashDispatcher dispatcher;

    private volatile AgentLoop loop;
    private volatile Model model;
    private volatile List<Message> history;
    private volatile SessionManager session;
    private volatile ContextCompactor compactor;

    public ReplContext(Path workdir, LlmClient llm, List<ToolDefinition> tools, String systemPrompt,
                       Supplier<InterruptTrigger> triggerSupplier, PrintStream out, Style style,
                       AgentLoop loop, Model model, List<Message> history,
                       SessionManager session, ContextCompactor compactor, SlashDispatcher dispatcher) {
        this.workdir = workdir;
        this.llm = llm;
        this.tools = tools;
        this.systemPrompt = systemPrompt;
        this.triggerSupplier = triggerSupplier;
        this.out = out == null ? System.out : out;
        this.style = style;
        this.loop = loop;
        this.model = model;
        this.history = history;
        this.session = session;
        this.compactor = compactor;
        this.dispatcher = dispatcher;
    }

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
