package dev.minicode.cli;

import dev.minicode.agent.AgentEvent;
import dev.minicode.agent.AgentLoop;
import dev.minicode.ai.*;
import dev.minicode.tools.*;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 命令行入口，对应 pi-coding-agent 的 cli（极简版）。
 * 用法：mini-code "你的需求描述"
 * 环境变量：.env 文件（推荐）或系统环境变量：OPENCODE_API_KEY、LLM_PROVIDER、LLM_MODEL、LLM_BASE_URL
 * 优先级：系统环境变量 > .env（从当前目录向上查找） > ~/.local/share/opencode/auth.json
 */
public class Main {

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
        System.out.println("[mini-code] 进入交互式 REPL（输入需求后回车，输入 exit/quit 退出）");
        LlmConfig cfgRepl = LlmConfig.resolve();
        if (cfgRepl.apiKey() == null) {
            System.err.println("[mini-code] 警告: 未找到 API Key，请检查 .env 或环境变量");
        } else {
            System.out.println("[mini-code] provider=" + cfgRepl.model().provider() + " model=" + cfgRepl.model().id() + " baseUrl=" + cfgRepl.model().baseUrl());
            System.out.println("[mini-code] 工作目录: " + workdir);
        }
        LlmClient llmRepl = new OpenAiCompatClient(cfgRepl.apiKey());
        Model modelRepl = cfgRepl.model();
        List<ToolDefinition> toolsRepl = List.of(new ReadTool(workdir), new WriteTool(workdir), new EditTool(workdir), new BashTool(workdir));
        AgentLoop loopRepl = new AgentLoop(llmRepl, modelRepl, buildSystemPrompt(workdir), toolsRepl, 20);
        List<Message> history = new ArrayList<>();
        // 区分管道 vs 交互式终端：System.console()==null 表示管道/重定向，此时一次性读完所有行后退出，避免 hasNextLine 阻塞
        if (System.console() == null) {
            // 管道模式：一次性读取 stdin 所有内容，按行处理
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
            for (String prompt : prompts) {
                runReplTurn(prompt, history, loopRepl);
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
                runScannerRepl(history, loopRepl);
            } else {
                try (Terminal t = terminal) {
                    runJLineRepl(t, history, loopRepl);
                } catch (IOException e) {
                    System.err.println("[mini-code] 关闭终端失败: " + e.getMessage());
                }
            }
        }
    }

    /**
     * JLine 交互循环：支持方向键编辑与历史，Ctrl-C 放弃当前行，Ctrl-D 退出。
     */
    static void runJLineRepl(Terminal terminal, List<Message> history, AgentLoop loop) throws Exception {
        LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();
        while (true) {
            String line;
            try {
                line = reader.readLine("\n> ");
            } catch (UserInterruptException e) {
                continue; // Ctrl-C：放弃当前行，继续下一轮
            } catch (EndOfFileException e) {
                System.out.println("[mini-code] 再见");
                break;
            }
            if (line == null) break;
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            if (isExitCommand(trimmed)) {
                System.out.println("[mini-code] 再见");
                break;
            }
            runReplTurn(line, history, loop);
        }
    }

    /**
     * 降级输入循环：终端初始化失败时使用，无行编辑能力（方向键显示为 ^[[D）。
     */
    static void runScannerRepl(List<Message> history, AgentLoop loop) throws Exception {
        java.util.Scanner scanner = new java.util.Scanner(System.in, StandardCharsets.UTF_8);
        while (true) {
            System.out.print("\n> ");
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
            runReplTurn(line, history, loop);
        }
    }

    /**
     * 是否退出命令（exit/quit//exit，大小写不敏感）。
     */
    static boolean isExitCommand(String trimmed) {
        return trimmed.equalsIgnoreCase("exit")
                || trimmed.equalsIgnoreCase("quit")
                || trimmed.equalsIgnoreCase("/exit");
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
     * one-shot 执行一次
     */
    private static void runOneTurn(String prompt, Path workdir) throws Exception {
        LlmConfig cfg = LlmConfig.resolve();
        if (cfg.apiKey() == null) {
            System.err.println("[mini-code] 警告: 未找到 provider " + cfg.model().provider() + " 的 API Key，请设置 " + cfg.model().provider() + " 的 Key（例如 OPENCODE_API_KEY）");
        } else {
            System.out.println("[mini-code] provider=" + cfg.model().provider() + " model=" + cfg.model().id() + " baseUrl=" + cfg.model().baseUrl());
        }
        LlmClient llm = new OpenAiCompatClient(cfg.apiKey());
        Model model = cfg.model();
        List<ToolDefinition> tools = List.of(new ReadTool(workdir), new WriteTool(workdir), new EditTool(workdir), new BashTool(workdir));
        AgentLoop loop = new AgentLoop(llm, model, buildSystemPrompt(workdir), tools, 20);
        List<Message> prompts = List.of(Message.user(prompt));
        System.out.println("[mini-code] 工作目录: " + workdir);
        System.out.println("---");
        List<Message> result = loop.run(prompts, Main::printEvent);
        result.stream().filter(m -> m.role == Message.Role.assistant).reduce((a, b) -> b).ifPresent(m -> {
            if ("error".equals(m.stopReason)) System.exit(2);
        });
    }

    /**
     * REPL 单轮，带历史
     */
    private static void runReplTurn(String prompt, List<Message> history, AgentLoop loop) throws Exception {
        List<Message> newPrompts = List.of(Message.user(prompt));
        List<Message> turnResult = loop.runWithHistory(history, newPrompts, Main::printEvent);
        history.addAll(turnResult);
        // 保留历史长度控制：超过 50 条则裁剪早期（MVP 简化）
        if (history.size() > 50) {
            int toRemove = history.size() - 50;
            history.subList(0, toRemove).clear();
        }
    }

    /**
     * 事件打印：统一经事件渲染器出字（交互与管道两模式共用）。
     * 样式由 Style.detect 决定，渲染器为纯函数（不读环境、不碰时钟）。
     */
    private static void printEvent(AgentEvent e) {
        // 样式探测：NO_COLOR 非空 / 非 tty / TERM=dumb 任一命中即去色
        Style style = Style.detect(System.getenv(), System.console() != null);
        String rendered = EventRenderer.render(e, style);
        if (rendered == null || rendered.isEmpty()) return;
        System.out.println(rendered);
    }

    /**
     * 供单测与未来耗时注入使用的显式样式入口（包可见）。
     */
    static void printEvent(AgentEvent e, Style style) {
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
}
