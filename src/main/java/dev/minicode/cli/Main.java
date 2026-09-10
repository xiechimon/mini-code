package dev.minicode.cli;

import dev.minicode.agent.AgentEvent;
import dev.minicode.agent.AgentLoop;
import dev.minicode.agent.InterruptTrigger;
import dev.minicode.ai.*;
import dev.minicode.session.ContextCompactor;
import dev.minicode.session.SessionHistory;
import dev.minicode.session.SessionManager;
import dev.minicode.tools.*;
import org.jline.utils.Signals;
import org.jline.keymap.KeyMap;
import org.jline.reader.Binding;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.Reference;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.DefaultParser;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.reader.impl.completer.StringsCompleter;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 命令行入口，对应 pi-coding-agent 的 cli（极简版）。
 * 用法：mini-code "你的需求描述"
 * 环境变量：.env 文件（推荐）或系统环境变量：OPENCODE_API_KEY、LLM_PROVIDER、LLM_MODEL、LLM_BASE_URL
 * 优先级：系统环境变量 > .env（从当前目录向上查找） > ~/.local/share/opencode/auth.json
 */
public class Main {

    /** 提示符字符 */
    private static final String PROMPT_CHAR = "❯";

    /**
     * SIGINT 触发器——将 Ctrl-C 映射为 AgentLoop 的取消信号。
     * <p>
     * 经 JLine 的公开 {@link org.jline.utils.Signals} 注册 SIGINT：JLine 内部封装 JVM 的 {@code sun.misc.Signal}——
     * 二者同为「JDK 无可移植公共 SIGINT 捕获 API」时的事实标准（{@code Runtime.addShutdownHook} 会终止 JVM 且无法区分信号）。
     * 本实现走 JLine 而非直连 {@code sun.misc}，避免引用内部 API 触发编译警告。
     * 流式期间注册，触发器 {@code close()} 时恢复原处理器，保证结束后 Ctrl-C 恢复为 JLine 的弃行/退出语义。
     * 受限或非 Unix 环境下捕获异常则降级为永不取消，不影响主流程。
     * </p>
     */
    static class SigIntInterruptTrigger implements InterruptTrigger {
        private volatile boolean cancelled = false;
        private Object prev; // Signals.register 返回的恢复句柄

        SigIntInterruptTrigger() {
            try {
                prev = Signals.register("INT", () -> cancelled = true);
            } catch (Throwable ignore) {
                prev = null;
            }
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public void close() {
            if (prev != null) {
                try {
                    Signals.unregister("INT", prev);
                } catch (Throwable ignore) {
                }
            }
        }
    }

    public static void main(String[] args) throws Exception {
        // 帮助信息
        if (args.length == 1 && ("--help".equals(args[0]) || "-h".equals(args[0]))) {
            System.out.println("用法: mini-code \"<你的需求>\"");
            System.out.println("示例: mini-code \"帮我把 README.md 的标题改成 Hello mini-code\"");
            System.out.println("示例: mini-code \"读取 src/Main.java 并修复其中的空指针问题\"");
            System.out.println();
            System.out.println("环境变量（可写在项目根目录 .env 文件中）：");
            System.out.println("  OPENCODE_API_KEY              # opencode/opencode-go 的 API Key（必填）");
            System.out.println("  LLM_PROVIDER=opencode-go      # 可选：opencode | opencode-go | deepseek | openai");
            System.out.println("  LLM_MODEL=kimi-k2.6           # 可选，默认 kimi-k2.6");
            System.out.println("  LLM_BASE_URL                  # 可选，自定义网关地址");
            System.out.println();
            System.out.println("提示: .env 文件会自动从当前目录向上查找到项目根目录");
            System.out.println("提示: 无参直接运行会进入交互式 REPL，多轮对话直到输入 exit");
            System.exit(0);
        }

        Path workdir = Path.of(System.getProperty("user.dir"));

        // 有参：one-shot 模式，直接执行一次后退出
        if (args.length > 0) {
            String prompt = String.join(" ", args);
            runOneTurn(prompt, workdir);
            return;
        }

        // 无参：REPL 多轮对话模式（解决“第一轮后自动退出”）
        System.out.println("[mini-code] 进入交互式 REPL（输入需求后回车，/help 查看命令，exit/quit 退出）");
        LlmConfig cfgRepl = LlmConfig.resolve();
        // 启动横幅一行：mini-code · provider/model · 工作目录（名称粗体、其余暗灰），去色时纯文本
        Style bannerStyle = Style.detect(System.getenv(), System.console() != null);
        String banner = EventRenderer.renderBanner(cfgRepl.model().provider(), cfgRepl.model().id(), workdir, bannerStyle);
        System.out.println(banner);
        if (cfgRepl.apiKey() == null) {
            System.err.println("[mini-code] 警告: 未找到 API Key，请检查 .env 或环境变量");
        }
        LlmClient llmRepl = new OpenAiCompatClient(cfgRepl.apiKey());
        Model modelRepl = cfgRepl.model();
        List<ToolDefinition> toolsRepl = List.of(new ReadTool(workdir), new WriteTool(workdir), new EditTool(workdir), new BashTool(workdir));
        // REPL 的流式触发器：交互式流式期间注册 SIGINT 映射到取消信号，结束后注销恢复原语义
        java.util.function.Supplier<InterruptTrigger> replTriggerSupplier = () -> new SigIntInterruptTrigger();
        AgentLoop loopRepl = new AgentLoop(llmRepl, modelRepl, buildSystemPrompt(workdir), toolsRepl, 20, replTriggerSupplier);
        // 会话持久化：REPL 多轮 append-only 树；建会话失败则降级为纯内存 history（不中断 REPL）
        SessionManager session = null;
        try {
            String home = System.getProperty("user.home");
            if (home == null || home.isEmpty()) home = ".";
            session = SessionManager.create(Path.of(home, ".mini-code"), workdir.toString());
        } catch (IOException e) {
            System.err.println("[mini-code] 会话初始化失败（降级为不持久化）: " + e.getMessage());
        }
        List<Message> history = session != null ? new SessionHistory(session) : new ArrayList<>();
        ContextCompactor compactor = session != null ? new ContextCompactor(session) : null;
        // 斜杠命令：注册表 + 会话上下文（命令操作面）。管道模式不解析命令，仅交互路径使用
        SlashDispatcher slash = new SlashDispatcher(SlashCommands.builtins());
        ReplContext replCtx = new ReplContext(workdir, llmRepl, toolsRepl, buildSystemPrompt(workdir),
                replTriggerSupplier, System.out, Style.detect(System.getenv(), System.console() != null),
                loopRepl, modelRepl, history, session, compactor, slash);
        // 区分管道 vs 交互式终端：System.console()==null 表示管道/重定向，此时一次性读完所有行后退出，避免 hasNextLine 阻塞
        if (System.console() == null) {
            // 管道模式：一次性读取 stdin 所有内容，按行处理；不启用流式与重绘，走同步路径，零流式事件
            String piped;
            try {
                piped = new String(System.in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) {
                piped = "";
            }
            String[] lines = piped.split("\\R");
            List<String> prompts = filterPipeLines(List.of(lines));
            if (prompts.isEmpty()) {
                System.out.println("[mini-code] 未从管道读取到有效输入，退出。");
            }
            AgentLoop pipelineLoop = new AgentLoop(llmRepl, modelRepl, buildSystemPrompt(workdir), toolsRepl, 20, () -> () -> false, false);
            for (String prompt : prompts) {
                runReplTurn(prompt, history, pipelineLoop);
            }
        } else {
            // 交互式终端：JLine 行编辑（方向键移动光标、上下翻历史），对齐 pi 的 node:readline。
            // Scanner 按行缓冲直读 stdin（cooked 模式），方向键转义序列(ESC [ D)会被当成普通字符，原样显示为 ^[[D。
            Terminal terminal = null;
            try {
                terminal = TerminalBuilder.builder().system(true).build();
            } catch (IOException e) {
                System.err.println("[mini-code] 终端初始化失败，回退到简单输入模式（方向键可能显示为 ^[[D）: " + e.getMessage());
            }
            if (terminal == null) {
                runScannerRepl(history, loopRepl, buildTurnComplete(compactor, llmRepl, modelRepl, workdir, history), replCtx);
            } else {
                try (Terminal t = terminal) {
                    runJLineRepl(t, history, loopRepl, defaultHistoryPath(),
                            buildTurnComplete(compactor, llmRepl, modelRepl, workdir, history), replCtx);
                } catch (IOException e) {
                    System.err.println("[mini-code] 关闭终端失败: " + e.getMessage());
                }
            }
        }
    }

    /**
     * 默认历史文件路径：~/.mini-code/history
     * <p>
     * 供 JLine FileHistory 持久化使用，路径可注入（测试用 @TempDir）。
     * </p>
     */
    /**
     * 构造 REPL 单轮结束触发的压缩钩子：用 lambda capture 闭包的形式拿到 history 与 workdir，
     * 既不破坏现有 runReplTurn/runJLineRepl 签名，又避免 ThreadLocal 暗流。
     */
    private static Runnable buildTurnComplete(ContextCompactor compactor, LlmClient llm, Model model,
                                             Path workdir, List<Message> history) {
        if (compactor == null || llm == null || model == null) return null;
        return () -> {
            try {
                if (!compactor.shouldCompact(history)) return;
                List<Message> kept = compactor.compact(history, llm, model,
                        buildSystemPrompt(workdir), model.id());
                if (kept == history) return;
                if (history instanceof SessionHistory sh) {
                    sh.replaceKeepingInMemory(kept);
                } else {
                    history.clear();
                    history.addAll(kept);
                }
                System.out.println("[mini-code] 已压缩上下文（保留段 " + kept.size() + " 条，完整会话存于 JSONL）");
            } catch (Exception e) {
                System.err.println("[mini-code] 压缩失败（已跳过）: " + e.getMessage());
            }
        };
    }

    static Path defaultHistoryPath() {
        String home = System.getProperty("user.home");
        if (home == null || home.isEmpty()) {
            home = ".";
        }
        return Path.of(home, ".mini-code", "history");
    }

    /**
     * 生成提示符：有色模式下青色，否则纯文本。
     * <p>
     * 与 {@link Style} 探测联动：{@code Style.detect(env, isTty)} 决定是否着色。
     * 复用 {@link Style#colorEnabled()}，零新依赖，手写 ANSI（ANSI 常量收敛至 {@link Style}）。
     * </p>
     *
     * @param style 样式开关（null 时按去色处理）
     * @return 提示符字符串（含尾空格，供 readLine 直接使用）
     */
    static String prompt(Style style) {
        if (style != null && style.colorEnabled()) {
            return Style.ANSI_CYAN + PROMPT_CHAR + Style.ANSI_RESET + " ";
        }
        return PROMPT_CHAR + " ";
    }

    /**
     * 创建带持久化历史与多行支持的 LineReader。
     * <p>
     * 配置要点：
     * - 历史：FileHistory 路径可注入，默认 {@link #defaultHistoryPath()}；禁用时间戳以保持明文可读，增量落盘
     * - 续行：行尾反斜杠通过 {@link DefaultParser#setEofOnEscapedNewLine(boolean)} 触发二次提示，JLine 自动拼接
     * - 粘贴：括号粘贴天然支持（BRACKETED_PASTE），对 dumb/ExternalTerminal 额外补绑定以通过测试
     * </p>
     *
     * @param terminal    终端
     * @param historyPath 历史文件路径（可为 null 表示不持久化）
     * @return 配置好的 LineReader
     */
    static LineReader createReader(Terminal terminal, Path historyPath) throws IOException {
        return createReader(terminal, historyPath, SlashCommands.completionNames(null));
    }

    /**
     * 创建带持久化历史、多行支持与斜杠命令 Tab 补全的 LineReader。
     * <p>
     * 补全仅作用于首 token（{@code line.wordIndex() == 0}），候选来自调度器注册表 +
     * {@code /exit} {@code /quit}——与 {@code /help} 同一份注册表，两者不会漂移。
     * </p>
     *
     * @param terminal            终端
     * @param historyPath         历史文件路径（可为 null 表示不持久化）
     * @param completionCandidates 首 token 补全候选（null/空 = 不安装补全）
     * @return 配置好的 LineReader
     */
    static LineReader createReader(Terminal terminal, Path historyPath, List<String> completionCandidates) throws IOException {
        if (historyPath != null) {
            try {
                Path parent = historyPath.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
            } catch (IOException ignored) {
                // 忽略目录创建失败，后续 save 时会再次尝试
            }
        }
        DefaultParser parser = new DefaultParser();
        // 行尾反斜杠续行：解析器在行尾为转义字符时抛 EOFError，触发二次提示并拼接
        parser.setEofOnEscapedNewLine(true);
        LineReaderBuilder builder = LineReaderBuilder.builder()
                .terminal(terminal)
                .parser(parser)
                .variable(LineReader.HISTORY_FILE, historyPath)
                .option(LineReader.Option.HISTORY_TIMESTAMPED, false)
                .option(LineReader.Option.HISTORY_INCREMENTAL, true)
                .option(LineReader.Option.BRACKETED_PASTE, true);
        if (completionCandidates != null && !completionCandidates.isEmpty()) {
            builder.completer((r, line, candidates) -> {
                if (line.wordIndex() == 0) {
                    new StringsCompleter(completionCandidates).complete(r, line, candidates);
                }
            });
        }
        LineReader reader = builder.build();
        // —— 护栏：为何反射 ——
        // 背景：JLine 3.27.1 在 dumb/ExternalTerminal 下默认 keyMap 为 "dumb"，未绑定 BRACKETED_PASTE 的 begin 序列 "\u001B[200~"；
        //       导致多行粘贴（bracketed paste）被拆成多次 readLine 提交，回退到逐行历史。
        // 做法：通过反射取 LineReaderImpl.keyMaps 中的 "dumb" KeyMap，手动补绑定 "\u001B[200~" → "begin-paste"；
        //       与 JLine 对 xterm/emacs 的 bindArrowKeys 逻辑保持一致，使 dumb 下粘贴也能整体进缓冲一次提交。
        // 降级：反射失败（如 JLine 内部字段改名、安全管理器限制）时不抛异常——dumb 粘贴回退为逐行提交，
        //       但不影响主流程（正常输入、历史持久化、反斜杠续行仍可用）；handleBracketedPasteFallback 兜底。
        // 可逆性：JLine 后续若在 dumb 上默认支持 bracketed paste，本补丁变为 no-op，可安全移除。
        if (reader instanceof LineReaderImpl) {
            try {
                java.lang.reflect.Field f = LineReaderImpl.class.getDeclaredField("keyMaps");
                f.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<String, KeyMap<Binding>> keyMaps = (Map<String, KeyMap<Binding>>) f.get(reader);
                KeyMap<Binding> dumb = keyMaps.get("dumb");
                if (dumb != null) {
                    // 为 dumb 补上 begin-paste 绑定，与 emacs 的 bindArrowKeys 保持一致
                    dumb.bind(new Reference("begin-paste"), "\u001B[200~");
                }
            } catch (Exception ignored) {
                // 反射失败降级：dumb 下粘贴仍会被拆成多次提交，但不影响主流程；外层有 handleBracketedPasteFallback 兜底
            }
        }
        return reader;
    }

    /**
     * JLine 交互循环：支持方向键编辑与历史，Ctrl-C 放弃当前行，Ctrl-D 退出。
     * <p>
     * 使用 {@link #defaultHistoryPath()} 作为历史文件，提示符经 {@link #prompt(Style)} 着色。
     * </p>
     */
    static void runJLineRepl(Terminal terminal, List<Message> history, AgentLoop loop) throws Exception {
        runJLineRepl(terminal, history, loop, defaultHistoryPath(), null, null);
    }

    static void runJLineRepl(Terminal terminal, List<Message> history, AgentLoop loop, Path historyPath,
                             Runnable onTurnComplete) throws Exception {
        runJLineRepl(terminal, history, loop, historyPath, onTurnComplete, null);
    }

    /**
     * JLine 交互循环（支持 turn-end 钩子与斜杠命令）。
     * <p>JLine 历史在首次 readLine 时 attach 并加载，此后增量落盘。</p>
     * <p>斜杠命令在退出检查之后、回合派发之前拦截：HANDLED 跳过本轮 LLM 调用与 onTurnComplete
     * （无 LLM 回合不触发自动压缩）；NOT_A_COMMAND 原样发给 LLM（pi fallthrough 终点语义）。</p>
     *
     * @param ctx REPL 会话上下文；null 表示不启用斜杠命令
     */
    static void runJLineRepl(Terminal terminal, List<Message> history, AgentLoop loop, Path historyPath,
                             Runnable onTurnComplete, ReplContext ctx) throws Exception {
        Style style = Style.detect(System.getenv(), true);
        if (terminal != null && "dumb".equals(terminal.getType())) {
            style = Style.PLAIN;
        } else if (terminal != null && "dumb-color".equals(terminal.getType())) {
            style = Style.PLAIN;
        }
        String promptStr = prompt(style);
        LineReader reader = createReader(terminal, historyPath, SlashCommands.completionNames(ctx != null ? ctx.dispatcher() : null));
        while (true) {
            String line;
            try {
                line = reader.readLine(promptStr);
            } catch (UserInterruptException e) {
                continue;
            } catch (EndOfFileException e) {
                System.out.println("[mini-code] 再见");
                break;
            }
            if (line == null) break;
            if (line.contains("\u001B[200~")) {
                line = handleBracketedPasteFallback(line, reader, promptStr);
                if (line == null) continue;
            }
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            if (isExitCommand(trimmed)) {
                System.out.println("[mini-code] 再见");
                break;
            }
            if (ctx != null && ctx.dispatcher() != null && trimmed.startsWith("/")) {
                SlashDispatcher.Result r = ctx.dispatcher().dispatch(trimmed, ctx);
                if (r == SlashDispatcher.Result.HANDLED) {
                    try { reader.getHistory().save(); } catch (IOException ignored) {}
                    continue;
                }
                if (r == SlashDispatcher.Result.EXIT) {
                    System.out.println("[mini-code] 再见");
                    break;
                }
            }
            int renderWidth = terminalWidth(terminal);
            int renderHeight = terminalHeight(terminal);
            runReplTurn(line, history, loop, style, renderWidth, renderHeight);
            if (onTurnComplete != null) onTurnComplete.run();
            try { reader.getHistory().save(); } catch (IOException ignored) {}
        }
    }

    /**
     * dumb 终端的括号粘贴兜底：当首行含  ESC[200~ 时，持续读取直到 ESC[201~，拼接为一次提交。
     * <p>
     * 正常终端（xterm）下 JLine 已处理，此方法仅在 dumb 反射补丁未生效或未触发时兜底。
     * </p>
     */
    static String handleBracketedPasteFallback(String firstLine, LineReader reader, String prompt) {
        String begin = "\u001B[200~";
        String end = "\u001B[201~";
        int bIdx = firstLine.indexOf(begin);
        if (bIdx < 0) return firstLine;
        StringBuilder buf = new StringBuilder();
        // 去掉起始标记
        String afterBegin = firstLine.substring(bIdx + begin.length());
        // 若首行已含结束标记，直接截断
        int eIdx = afterBegin.indexOf(end);
        if (eIdx >= 0) {
            buf.append(afterBegin, 0, eIdx);
            return buf.toString();
        }
        buf.append(afterBegin);
        // 首行未含结束标记，需继续读取
        // 若首行后无内容但有换行语义，补一个换行（粘贴多行场景）
        // 若后继行通过 JLine 括号粘贴已合并，此处不会走到
        while (true) {
            String next;
            try {
                // 二次提示用空或同提示，保持简单
                next = reader.readLine("");
            } catch (UserInterruptException e) {
                return null;
            } catch (EndOfFileException e) {
                break;
            }
            if (next == null) break;
            int endIdx = next.indexOf(end);
            if (endIdx >= 0) {
                buf.append("\n").append(next, 0, endIdx);
                break;
            } else {
                // 正常行，追加并补换行
                buf.append("\n").append(next);
            }
        }
        return buf.toString();
    }

    /**
     * 降级输入循环：终端初始化失败时使用，无行编辑能力（方向键显示为 ^[[D）。
     */
    static void runScannerRepl(List<Message> history, AgentLoop loop) throws Exception {
        runScannerRepl(history, loop, null, null);
    }

    static void runScannerRepl(List<Message> history, AgentLoop loop, Runnable onTurnComplete) throws Exception {
        runScannerRepl(history, loop, onTurnComplete, null);
    }

    /**
     * Scanner 降级循环（支持 turn-end 钩子与斜杠命令，拦截语义同 {@link #runJLineRepl}）。
     *
     * @param ctx REPL 会话上下文；null 表示不启用斜杠命令
     */
    static void runScannerRepl(List<Message> history, AgentLoop loop, Runnable onTurnComplete, ReplContext ctx) throws Exception {
        java.util.Scanner scanner = new java.util.Scanner(System.in, StandardCharsets.UTF_8);
        Style style = Style.detect(System.getenv(), true);
        String promptStr = prompt(style);
        int width = resolveWidth(null);
        int height = resolveViewportRows();
        while (true) {
            System.out.print(promptStr);
            System.out.flush();
            if (!scanner.hasNextLine()) break;
            String line = scanner.nextLine();
            if (line == null) break;
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            if (isExitCommand(trimmed)) {
                System.out.println("[mini-code] 再见");
                break;
            }
            if (ctx != null && ctx.dispatcher() != null && trimmed.startsWith("/")) {
                SlashDispatcher.Result r = ctx.dispatcher().dispatch(trimmed, ctx);
                if (r == SlashDispatcher.Result.HANDLED) {
                    continue;
                }
                if (r == SlashDispatcher.Result.EXIT) {
                    System.out.println("[mini-code] 再见");
                    break;
                }
            }
            // 降级输入也走流式路径，与 JLine 交互同款（SIGINT 由 loop 的 triggerSupplier 接管）
            runReplTurn(line, history, loop, style, width, height);
            if (onTurnComplete != null) onTurnComplete.run();
        }
    }

    /**
     * 是否退出命令（exit/quit//exit//quit，大小写不敏感）。
     * <p>
     * 退出不注册进斜杠命令表：管道截断（{@link #filterPipeLines}）与交互循环共用本特判，
     * 对齐 pi 的 /quit 命名（见 .scratch/slash-commands/spec.md）。
     * </p>
     */
    static boolean isExitCommand(String trimmed) {
        return trimmed.equalsIgnoreCase("exit")
                || trimmed.equalsIgnoreCase("quit")
                || trimmed.equalsIgnoreCase("/exit")
                || trimmed.equalsIgnoreCase("/quit");
    }

    /**
     * 管道输入过滤：去空行，遇退出命令截断（与交互循环语义一致）。
     */
    static List<String> filterPipeLines(List<String> lines) {
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            if (isExitCommand(trimmed)) break;
            out.add(line);
        }
        return out;
    }

    /**
     * one-shot 执行一次——tty 时与 REPL 同款块级流式体验：注册 SIGINT 触发器 + BlockStreamer 块级渲染；
     * 管道（非 tty）时走同步路径，零流式事件、无逐字与控制序列。
     */
    private static void runOneTurn(String prompt, Path workdir) throws Exception {
        LlmConfig cfg = LlmConfig.resolve();
        // 启动横幅一行：mini-code · provider/model · 工作目录（名称粗体、其余暗灰），无 key 警告保持走 stderr
        Style bannerStyle = Style.detect(System.getenv(), System.console() != null);
        String banner = EventRenderer.renderBanner(cfg.model().provider(), cfg.model().id(), workdir, bannerStyle);
        System.out.println(banner);
        if (cfg.apiKey() == null) {
            System.err.println("[mini-code] 警告: 未找到 provider " + cfg.model().provider() + " 的 API Key，请设置 " + cfg.model().provider() + " 的 Key（例如 OPENCODE_API_KEY）");
        }
        LlmClient llm = new OpenAiCompatClient(cfg.apiKey());
        Model model = cfg.model();
        List<ToolDefinition> tools = List.of(new ReadTool(workdir), new WriteTool(workdir), new EditTool(workdir), new BashTool(workdir));
        boolean isTty = System.console() != null;
        Style renderStyle = bannerStyle;
        int renderWidth = resolveWidth(null);
        List<Message> prompts = List.of(Message.user(prompt));
        System.out.println("---");
        long startNanos = System.nanoTime();
        int[] turns = {0};
        int[] toolCalls = {0};
        if (isTty) {
            java.util.function.Supplier<InterruptTrigger> triggerSupplier = () -> new SigIntInterruptTrigger();
            AgentLoop loop = new AgentLoop(llm, model, buildSystemPrompt(workdir), tools, 20, triggerSupplier);
            BlockStreamer streamer = new BlockStreamer(renderStyle, renderWidth, resolveViewportRows(), System.out);
            AgentLoop.EventSink sink = streamingSink(streamer, startNanos, turns, toolCalls);
            List<Message> result = loop.run(prompts, sink);
            result.stream().filter(m -> m.role == Message.Role.assistant).reduce((a, b) -> b).ifPresent(m -> {
                if ("error".equals(m.stopReason)) System.exit(2);
            });
        } else {
            AgentLoop loop = new AgentLoop(llm, model, buildSystemPrompt(workdir), tools, 20, () -> () -> false, false);
            AgentLoop.EventSink sink = e -> {
                if (e instanceof AgentEvent.TurnStart) {
                    turns[0]++;
                } else if (e instanceof AgentEvent.ToolResultEvent) {
                    toolCalls[0]++;
                }
                String rendered;
                if (e instanceof AgentEvent.AgentEnd) {
                    Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
                    rendered = EventRenderer.render(e, renderStyle, TurnStats.of(elapsed, turns[0], toolCalls[0]), renderWidth);
                } else {
                    rendered = EventRenderer.render(e, renderStyle, renderWidth);
                }
                if (rendered == null || rendered.isEmpty()) return;
                System.out.println(rendered);
            };
            List<Message> result = loop.run(prompts, sink);
            result.stream().filter(m -> m.role == Message.Role.assistant).reduce((a, b) -> b).ifPresent(m -> {
                if ("error".equals(m.stopReason)) System.exit(2);
            });
        }
    }

    /**
     * REPL 单轮，带历史（计时与事件流计数在此注入渲染器）。
     * 管道模式真正非流式——不注册 SIGINT、不产生光标控制序列，走同步 chat 等价路径，
     * 零流式事件 发射、无逐字输出行为，仅在 MessageEnd/AgentEnd 输出最终渲染。
     */
    private static void runReplTurn(String prompt, List<Message> history, AgentLoop loop) throws Exception {
        Style style = Style.detect(System.getenv(), System.console() != null);
        int width = resolveWidth(null);
        if (style == null) style = Style.PLAIN;
        width = AnsiTextUtil.normalizeWidth(width);
        long startNanos = System.nanoTime();
        int[] turns = {0};
        int[] toolCalls = {0};
        Style s = style;
        int w = width;
        AgentLoop.EventSink sink = e -> {
            if (e instanceof AgentEvent.TurnStart) {
                turns[0]++;
            } else if (e instanceof AgentEvent.ToolResultEvent) {
                toolCalls[0]++;
            }
            String rendered;
            if (e instanceof AgentEvent.AgentEnd) {
                Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
                rendered = EventRenderer.render(e, s, TurnStats.of(elapsed, turns[0], toolCalls[0]), w);
            } else {
                rendered = EventRenderer.render(e, s, w);
            }
            if (rendered == null || rendered.isEmpty()) return;
            System.out.println(rendered);
        };
        List<Message> newPrompts = List.of(Message.user(prompt));
        List<Message> turnResult = loop.runWithHistory(history, newPrompts, sink);
        history.addAll(turnResult);
    }

    /**
     * REPL 单轮（带样式与宽度注入，供 JLine 交互路径复用，流式）。
     * <p>
     * 交互流式路径：持有 {@link BlockStreamer}（块边界检测 + 单行进度指示）并在 EventSink 中处理
     * {@link AgentEvent.MessageUpdate} 的直出与首片段覆盖、MessageEnd 的回退重绘与 aborted 标记；
     * SIGINT 映射由 AgentLoop 的 triggerSupplier（SigIntInterruptTrigger）在流式期间注册/注销，
     * 保证 Ctrl-C 仅取消本轮生成而不退进程，且结束后恢复 JLine 的弃行语义。
     * </p>
     */
    static void runReplTurn(String prompt, List<Message> history, AgentLoop loop, Style style, int width, int viewportRows) throws Exception {
        if (style == null) style = Style.PLAIN;
        width = AnsiTextUtil.normalizeWidth(width);
        long startNanos = System.nanoTime();
        int[] turns = {0};
        int[] toolCalls = {0};
        Style s = style;
        int w = width;
        BlockStreamer streamer = new BlockStreamer(s, w, viewportRows, System.out);
        AgentLoop.EventSink sink = streamingSink(streamer, startNanos, turns, toolCalls);
        List<Message> newPrompts = List.of(Message.user(prompt));
        List<Message> turnResult = loop.runWithHistory(history, newPrompts, sink);
        history.addAll(turnResult);
    }

    /**
     * 组装流式等待反馈面的输出 sink：块级流式渲染器 + 事件渲染与轮次/工具计数。
     * 供 one-shot tty 与 REPL 交互路径复用，避免两处重复的事件分发逻辑。
     */
    private static AgentLoop.EventSink streamingSink(BlockStreamer streamer,
                                                     long startNanos, int[] turns, int[] toolCalls) {
        return e -> {
            if (e instanceof AgentEvent.TurnStart) {
                turns[0]++;
                String rendered = EventRenderer.render(e, streamer.style(), streamer.width());
                if (rendered == null || rendered.isEmpty()) return;
                System.out.println(rendered);
                return;
            } else if (e instanceof AgentEvent.MessageStart) {
                streamer.reset();                        // 消息开始：刷新流式状态，重武装占位行供首个 MessageUpdate 覆盖
                return;
            } else if (e instanceof AgentEvent.MessageUpdate mu) {
                streamer.delta(mu.delta());
                return;
            } else if (e instanceof AgentEvent.MessageEnd me) {
                streamer.flush("aborted".equals(me.message().stopReason));
                return;
            } else if (e instanceof AgentEvent.AgentEnd ae) {
                Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
                String rendered = EventRenderer.render(ae, streamer.style(), TurnStats.of(elapsed, turns[0], toolCalls[0]), streamer.width());
                if (rendered == null || rendered.isEmpty()) return;
                System.out.println(rendered);
                return;
            } else if (e instanceof AgentEvent.ToolResultEvent) {
                toolCalls[0]++;
            }
            String rendered = EventRenderer.render(e, streamer.style(), streamer.width());
            if (rendered == null || rendered.isEmpty()) return;
            System.out.println(rendered);
        };
    }

    /**
     * 事件打印：统一经事件渲染器出字。流式增量由 BlockStreamer 在交互路径处理，不经此入口。
     */
    private static void printEvent(AgentEvent e) {
        Style style = Style.detect(System.getenv(), System.console() != null);
        String rendered = EventRenderer.render(e, style);
        if (rendered == null || rendered.isEmpty()) return;
        System.out.println(rendered);
    }

    /**
     * 构造系统提示词
     */
    private static String buildSystemPrompt(Path workdir) {
        return """
                你是 mini-code，一个用 Java 实现的极简 Claude Code 克隆。
                你拥有的工具：read（读文件）、write（写文件）、edit（精确编辑）、bash（执行命令）。
                - 编辑前务必先 read 读取文件
                - 小改动用 edit，新文件用 write
                - 需要列目录、搜索、跑测试时用 bash
                - 回复简洁
                工作目录：%s
                """.formatted(workdir);
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }

    /**
     * 解析终端宽度：交互取终端宽度，管道固定 80；非法/零宽回退默认宽度。
     * 纯探测 helper，不读业务状态，失败不抛异常。
     */
    static int terminalWidth(Terminal terminal) {
        if (terminal != null) {
            try {
                int w = terminal.getWidth();
                if (w > 0) return w;
            } catch (Exception ignored) {
            }
        }
        return AnsiTextUtil.DEFAULT_WIDTH;
    }

    /** 终端高度：交互取终端高度，非法/零回退无上限（Integer.MAX_VALUE，即不启用视图外 cap）。 */
    static int terminalHeight(Terminal terminal) {
        if (terminal != null) {
            try {
                int h = terminal.getHeight();
                if (h > 0) return h;
            } catch (Exception ignored) {
            }
        }
        return Integer.MAX_VALUE;
    }

    /** 单次 / 管道路径的视口高度解析：有终端取高度，否则无上限（不启用 cap）。 */
    static int resolveViewportRows() {
        if (System.console() == null) return Integer.MAX_VALUE;
        try (Terminal t = TerminalBuilder.builder().system(true).build()) {
            return terminalHeight(t);
        } catch (Exception ignored) {
        }
        return Integer.MAX_VALUE;
    }

    /**
     * 注入宽度解析：有终端取终端宽度，无终端或非 tty 固定默认宽度。
     * one-shot / 管道路径复用；捕获异常回退默认宽度，保持纯函数调用方不崩。
     */
    static int resolveWidth(Terminal terminal) {
        boolean isTty = System.console() != null;
        if (!isTty) return AnsiTextUtil.DEFAULT_WIDTH;
        if (terminal != null) {
            int w = terminalWidth(terminal);
            if (w != AnsiTextUtil.DEFAULT_WIDTH || terminal.getWidth() > 0) return w;
        }
        // isTty 但未传入终端：尝试按系统终端探测（one-shot 场景）
        try (Terminal t = TerminalBuilder.builder().system(true).build()) {
            int w = t.getWidth();
            if (w > 0) return w;
        } catch (Exception ignored) {
        }
        return AnsiTextUtil.DEFAULT_WIDTH;
    }
}
