package dev.minicode.cli;

import dev.minicode.agent.AgentEvent;
import dev.minicode.agent.AgentLoop;
import dev.minicode.ai.*;
import dev.minicode.tools.*;

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
            boolean didWork = false;
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                if (trimmed.equalsIgnoreCase("exit") || trimmed.equalsIgnoreCase("quit") || trimmed.equalsIgnoreCase("/exit"))
                    break;
                runReplTurn(line, history, loopRepl);
                didWork = true;
            }
            if (!didWork) {
                System.out.println("[mini-code] 未从管道读取到有效输入，退出。");
            }
        } else {
            // 交互式终端：阻塞式 REPL
            java.util.Scanner scanner = new java.util.Scanner(System.in, java.nio.charset.StandardCharsets.UTF_8);
            while (true) {
                System.out.print("\n> ");
                System.out.flush();
                if (!scanner.hasNextLine()) break;
                String line = scanner.nextLine();
                if (line == null) break;
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                if (trimmed.equalsIgnoreCase("exit") || trimmed.equalsIgnoreCase("quit") || trimmed.equalsIgnoreCase("/exit")) {
                    System.out.println("[mini-code] 再见");
                    break;
                }
                runReplTurn(line, history, loopRepl);
            }
        }
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
        System.out.println("[mini-code] 需求: " + prompt);
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
        System.out.println("[mini-code] 需求: " + prompt);
        System.out.println("---");
        List<Message> newPrompts = List.of(Message.user(prompt));
        List<Message> turnResult = loop.runWithHistory(history, newPrompts, Main::printEvent);
        history.addAll(turnResult);
        // 保留历史长度控制：超过 50 条则裁剪早期（MVP 简化）
        if (history.size() > 50) {
            int toRemove = history.size() - 50;
            history.subList(0, toRemove).clear();
        }
    }

    private static void printEvent(AgentEvent e) {
        if (e instanceof AgentEvent.TurnStart t) {
            System.out.println("\n[第 " + t.turn() + " 轮] 思考中...");
        } else if (e instanceof AgentEvent.MessageEnd m) {
            Message msg = m.message();
            if (msg.role == Message.Role.assistant) {
                String txt = msg.text();
                if (!txt.isBlank()) System.out.println(txt);
                for (Message.ToolCall tc : msg.toolCalls()) {
                    System.out.println("→ 调用工具: " + tc.name + " " + tc.argumentsJson);
                }
            }
        } else if (e instanceof AgentEvent.ToolResultEvent tr) {
            System.out.println("← " + tr.toolCall().name + (tr.isError() ? " [失败]" : "") + ": " + truncate(tr.output(), 800));
        } else if (e instanceof AgentEvent.AgentEnd ae) {
            System.out.println("\n[mini-code] 完成（共 " + ae.messages().size() + " 条消息）");
        }
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
