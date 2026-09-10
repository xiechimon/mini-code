package dev.minicode.cli;

import dev.minicode.ai.Message;
import dev.minicode.session.ContextCompactor;
import dev.minicode.session.SessionHistory;
import dev.minicode.session.SessionManager;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内置斜杠命令表（对齐 pi 的 core/slash-commands.ts 单模块命令表）。
 * <p>
 * {@link LinkedHashMap} 注册序即 {@code /help} 展示序。{@code /exit} {@code /quit} 走
 * {@code Main.isExitCommand} 特判（与管道截断共用一条路径），不注册进表；
 * 但 {@code /help} 与 Tab 补全仍会列出它们，保证可发现。
 * </p>
 */
public final class SlashCommands {

    /** 一条已注册命令：一行说明 + 行为。 */
    public record Entry(String description, SlashCommand command) {
    }

    private SlashCommands() {
    }

    /** 内置命令表（注册序 = {@code /help} 展示序）。 */
    public static LinkedHashMap<String, Entry> builtins() {
        LinkedHashMap<String, Entry> m = new LinkedHashMap<>();
        m.put("help", new Entry("列出全部命令与说明", SlashCommands::help));
        m.put("session", new Entry("显示会话文件、sessionId、消息数与 token 估算", SlashCommands::session));
        m.put("compact", new Entry("立即压缩上下文（无视自动阈值）", SlashCommands::compact));
        return m;
    }

    /** Tab 补全候选（带 slash 前缀，含注册表外的 /exit /quit）。 */
    public static List<String> completionNames(SlashDispatcher dispatcher) {
        List<String> names = new ArrayList<>();
        Map<String, Entry> table = dispatcher != null ? dispatcher.commands() : builtins();
        for (String k : table.keySet()) {
            names.add("/" + k);
        }
        names.add("/exit");
        names.add("/quit");
        return names;
    }

    // ===== 内置命令实现 =====

    private static void help(String args, ReplContext ctx) {
        PrintStream out = ctx.out();
        Map<String, Entry> table = ctx.dispatcher() != null ? ctx.dispatcher().commands() : builtins();
        final int pad = Math.max(table.keySet().stream().mapToInt(String::length).max().orElse(0),
                "exit".length()) + 1;
        out.println(bold(ctx, "可用命令："));
        table.forEach((name, e) -> out.println("  " + dim(ctx, "/" + padRight(name, pad)) + e.description()));
        out.println("  " + dim(ctx, "/" + padRight("exit", pad)) + "退出 REPL（别名 /quit）");
        out.println(dim(ctx, "未识别的 /命令 将原样发给模型。"));
    }

    private static void session(String args, ReplContext ctx) {
        PrintStream out = ctx.out();
        SessionManager sm = ctx.session();
        if (sm == null) {
            out.println("[mini-code] 会话不可用（降级模式：未持久化，仅内存 history）");
            return;
        }
        List<Message> history = ctx.history();
        int size = history != null ? history.size() : 0;
        ContextCompactor compactor = ctx.compactor();
        out.println(bold(ctx, "会话"));
        out.println("  " + dim(ctx, "文件：     ") + sm.filePath());
        out.println("  " + dim(ctx, "sessionId: ") + sm.sessionId());
        out.println("  " + dim(ctx, "消息数：   ") + size + "（内存）");
        if (compactor != null) {
            String modelId = ctx.model() != null ? ctx.model().id() : null;
            int tokens = compactor.estimateTokensWithGuards(history, modelId);
            long threshold = compactor.compactionThresholdTokens();
            out.println("  " + dim(ctx, "token：    ") + "~" + tokens + " / 阈值 " + threshold
                    + "（余量 ~" + Math.max(0, threshold - tokens) + "）");
        }
    }

    private static void compact(String args, ReplContext ctx) throws Exception {
        PrintStream out = ctx.out();
        ContextCompactor compactor = ctx.compactor();
        if (compactor == null || ctx.llm() == null || ctx.model() == null) {
            out.println("[mini-code] 压缩不可用（无持久化会话或未配置 LLM）");
            return;
        }
        List<Message> history = ctx.history();
        if (history == null || history.isEmpty()) {
            out.println("[mini-code] 无需压缩（history 为空）");
            return;
        }
        String modelId = ctx.model().id();
        int before = compactor.estimateTokensWithGuards(history, modelId);
        List<Message> kept = compactor.compact(history, ctx.llm(), ctx.model(), ctx.systemPrompt(), modelId);
        if (kept.size() >= history.size()) {
            out.println("[mini-code] 当前上下文无需压缩（保留段已覆盖全部 ~" + before + " token）");
            return;
        }
        // 与 Main.buildTurnComplete 同款投影：SessionHistory 走仅内存替换，普通 List 清后重填
        if (history instanceof SessionHistory sh) {
            sh.replaceKeepingInMemory(kept);
        } else {
            history.clear();
            history.addAll(kept);
        }
        int after = compactor.estimateTokensWithGuards(history, modelId);
        out.println("[mini-code] 已压缩上下文：~" + before + " → ~" + after + " token"
                + "（保留段 " + kept.size() + " 条，完整会话存于 JSONL）");
    }

    // ===== 样式辅助（命令输出统一走 Style 常量，去色环境纯文本） =====

    private static String dim(ReplContext ctx, String s) {
        Style st = ctx.style();
        return st != null && st.colorEnabled() ? Style.ANSI_DIM + s + Style.ANSI_RESET : s;
    }

    private static String bold(ReplContext ctx, String s) {
        Style st = ctx.style();
        return st != null && st.colorEnabled() ? Style.ANSI_BOLD + s + Style.ANSI_RESET : s;
    }

    private static String padRight(String s, int n) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < n) {
            sb.append(' ');
        }
        return sb.toString();
    }
}
