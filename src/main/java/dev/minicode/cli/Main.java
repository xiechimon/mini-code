package dev.minicode.cli;

import dev.minicode.agent.AgentEvent;
import dev.minicode.agent.AgentLoop;
import dev.minicode.ai.*;
import dev.minicode.tools.*;

import java.nio.file.Path;
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
            System.out.println("");
            System.out.println("环境变量（可写在项目根目录 .env 文件中）：");
            System.out.println("  OPENCODE_API_KEY              # opencode/opencode-go 的 API Key（必填）");
            System.out.println("  LLM_PROVIDER=opencode-go      # 可选：opencode | opencode-go | deepseek | openai");
            System.out.println("  LLM_MODEL=kimi-k2.6           # 可选，默认 kimi-k2.6");
            System.out.println("  LLM_BASE_URL                  # 可选，自定义网关地址");
            System.out.println("");
            System.out.println("提示: .env 文件会自动从当前目录向上查找到项目根目录");
            System.out.println("提示: 无参直接运行会进入交互式，输入需求后回车即可");
            System.exit(0);
        }

        String prompt;
        if (args.length == 0) {
            // 无参交互式兜底：直接点 Run 也能用
            System.out.println("[mini-code] 未传入参数，进入交互式（输入需求后回车，Ctrl+C 退出）");
            System.out.print("> ");
            System.out.flush();
            java.util.Scanner scanner = new java.util.Scanner(System.in, java.nio.charset.StandardCharsets.UTF_8);
            StringBuilder sb = new StringBuilder();
            while (scanner.hasNextLine()) {
                String line = scanner.nextLine();
                if (line == null) break;
                // 空行结束（兼容多行粘贴，空行提交）
                if (line.isBlank() && sb.length() > 0) break;
                if (sb.length() > 0) sb.append("\n");
                sb.append(line);
                // 单行够用时直接跳出，等待模型执行；多行可空行结束
                if (sb.length() > 0 && !scanner.hasNextLine()) break;
                // 如果用户只输了一行且下一行还没输入，我们先尝试只读一行就执行（避免卡住）
                // 简化：读到第一行非空就直接作为 prompt（多行需求可用参数方式传入）
                break;
            }
            prompt = sb.toString().trim();
            if (prompt.isBlank()) {
                System.out.println("未输入需求，退出。提示：也可这样运行：java -jar mini-code.jar \"你的需求\"");
                System.exit(0);
                return;
            }
        } else {
            prompt = String.join(" ", args);
        }
        Path workdir = Path.of(System.getProperty("user.dir"));

        // 解析大模型配置
        LlmConfig cfg = LlmConfig.resolve();
        if (cfg.apiKey == null) {
            System.err.println("[mini-code] 警告: 未找到 provider " + cfg.model.provider() + " 的 API Key，请设置 " + cfg.model.provider() + " 的 Key（例如 OPENCODE_API_KEY）");
        } else {
            System.out.println("[mini-code] provider=" + cfg.model.provider() + " model=" + cfg.model.id() + " baseUrl=" + cfg.model.baseUrl());
        }

        LlmClient llm = new OpenAiCompatClient(cfg.apiKey);
        Model model = cfg.model;

        // 注册可用工具
        List<ToolDefinition> tools = List.of(
                new ReadTool(workdir),
                new WriteTool(workdir),
                new EditTool(workdir),
                new BashTool(workdir)
        );

        String systemPrompt = buildSystemPrompt(workdir);

        AgentLoop loop = new AgentLoop(llm, model, systemPrompt, tools, 20);
        List<Message> prompts = List.of(Message.user(prompt));

        System.out.println("[mini-code] 需求: " + prompt);
        System.out.println("[mini-code] 工作目录: " + workdir);
        System.out.println("---");

        List<Message> result = loop.run(prompts, e -> {
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
        });

        // 若最后一条是错误，返回非零退出码
        result.stream()
                .filter(m -> m.role == Message.Role.assistant)
                .reduce((a, b) -> b)
                .ifPresent(m -> {
                    if ("error".equals(m.stopReason)) System.exit(2);
                });
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
