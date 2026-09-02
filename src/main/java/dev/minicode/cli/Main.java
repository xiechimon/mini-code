package dev.minicode.cli;

import dev.minicode.agent.AgentEvent;
import dev.minicode.agent.AgentLoop;
import dev.minicode.ai.*;
import dev.minicode.tools.*;

import java.nio.file.Path;
import java.util.List;

/**
 * CLI entry — mirrors pi-coding-agent cli but minimal.
 * Usage: mini-code "your prompt"
 * Env: OPENCODE_API_KEY, LLM_PROVIDER, LLM_MODEL, LLM_BASE_URL
 */
public class Main {

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || "--help".equals(args[0]) || "-h".equals(args[0])) {
            System.out.println("Usage: mini-code \"<prompt>\"");
            System.out.println("Env: OPENCODE_API_KEY, LLM_PROVIDER (opencode|opencode-go|deepseek|openai), LLM_MODEL, LLM_BASE_URL");
            System.exit(0);
        }

        String prompt = String.join(" ", args);
        Path workdir = Path.of(System.getProperty("user.dir"));

        // resolve LLM
        LlmConfig cfg = LlmConfig.resolve();
        if (cfg.apiKey == null) {
            System.err.println("[mini-code] WARNING: no API key found for provider " + cfg.model.provider() + ". Set " + cfg.model.provider() + " key (e.g. OPENCODE_API_KEY). Running will produce error messages from LLM.");
        } else {
            System.out.println("[mini-code] provider=" + cfg.model.provider() + " model=" + cfg.model.id() + " baseUrl=" + cfg.model.baseUrl());
        }

        LlmClient llm = new OpenAiCompatClient(cfg.apiKey);
        Model model = cfg.model;

        List<ToolDefinition> tools = List.of(
                new ReadTool(workdir),
                new WriteTool(workdir),
                new EditTool(workdir),
                new BashTool(workdir)
        );

        String systemPrompt = buildSystemPrompt(workdir);

        AgentLoop loop = new AgentLoop(llm, model, systemPrompt, tools, 20);
        List<Message> prompts = List.of(Message.user(prompt));

        System.out.println("[mini-code] prompt: " + prompt);
        System.out.println("[mini-code] workdir: " + workdir);
        System.out.println("---");

        List<Message> result = loop.run(prompts, e -> {
            if (e instanceof AgentEvent.TurnStart t) {
                System.out.println("\n[turn " + t.turn() + "] thinking...");
            } else if (e instanceof AgentEvent.MessageEnd m) {
                Message msg = m.message();
                if (msg.role == Message.Role.assistant) {
                    String txt = msg.text();
                    if (!txt.isBlank()) System.out.println(txt);
                    for (Message.ToolCall tc : msg.toolCalls()) {
                        System.out.println("→ tool call: " + tc.name + " " + tc.argumentsJson);
                    }
                }
            } else if (e instanceof AgentEvent.ToolResultEvent tr) {
                System.out.println("← " + tr.toolCall().name + (tr.isError() ? " [error]" : "") + ": " + truncate(tr.output(), 800));
            } else if (e instanceof AgentEvent.AgentEnd ae) {
                System.out.println("\n[mini-code] done (" + ae.messages().size() + " messages)");
            }
        });

        // print final assistant text
        result.stream()
                .filter(m -> m.role == Message.Role.assistant)
                .reduce((a, b) -> b)
                .ifPresent(m -> {
                    if ("error".equals(m.stopReason)) System.exit(2);
                });
    }

    private static String buildSystemPrompt(Path workdir) {
        return """
                You are mini-code, a minimal Claude Code clone in Java.
                You have tools: read, write, edit, bash.
                - Always read a file before editing it.
                - Prefer edit for small changes, write for new files.
                - Use bash for ls, grep, tests.
                - Be concise.
                Workdir: %s
                """.formatted(workdir);
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }
}
