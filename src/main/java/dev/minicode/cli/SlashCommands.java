package dev.minicode.cli;

import dev.minicode.ai.Message;
import dev.minicode.ai.Model;
import dev.minicode.session.ContextCompactor;
import dev.minicode.session.SessionHistory;
import dev.minicode.session.SessionManager;

import java.io.PrintStream;
import java.nio.file.Path;
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
        m.put("model", new Entry("查看当前模型；/model <id> 切换（限同 provider）", SlashCommands::model));
        m.put("new", new Entry("开新会话（旧会话 JSONL 留盘）", SlashCommands::newSession));
        m.put("export", new Entry("导出会话 JSONL：/export [file]", SlashCommands::export));
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

    /**
     * 命令提示 Completer：候选 = 注册表 Entry（value=/name，descr=一行说明），
     * 外加表外的 /exit /quit。/help 与本 Completer 共用同一注册表，展示不漂移。
     * 仅首 token（wordIndex==0）出候选。
     */
    public static org.jline.reader.Completer commandCompleter(SlashDispatcher dispatcher) {
        return (reader, line, candidates) -> {
            if (line.wordIndex() != 0) return;
            Map<String, Entry> table = dispatcher != null ? dispatcher.commands() : builtins();
            table.forEach((name, e) -> candidates.add(
                    new org.jline.reader.Candidate("/" + name, "/" + name, null, e.description(),
                            null, null, true)));
            String exitDesc = "退出 REPL";
            candidates.add(new org.jline.reader.Candidate("/exit", "/exit", null, exitDesc, null, null, true));
            candidates.add(new org.jline.reader.Candidate("/quit", "/quit", null, exitDesc, null, null, true));
        };
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

    /** /model：无参显示当前模型；带参在同 provider 内切换 id。 */
    private static void model(String args, ReplContext ctx) {
        PrintStream out = ctx.out();
        Model cur = ctx.model();
        if (cur == null) {
            out.println("[mini-code] 模型不可用（未配置）");
            return;
        }
        if (args.isEmpty()) {
            out.println(bold(ctx, "当前模型"));
            out.println("  " + dim(ctx, "provider: ") + cur.provider());
            out.println("  " + dim(ctx, "model:    ") + cur.id());
            out.println("  " + dim(ctx, "baseUrl:  ") + cur.baseUrl());
            return;
        }
        // v1 限同 provider：参数即模型 id；含 / 或 : 的写法按跨 provider 意图拒绝（见 docs/adr/0006）
        if (args.contains("/") || args.contains(":")) {
            out.println("[mini-code] v1 仅支持同 provider 内切换模型 id（收到 " + args
                    + "）；跨 provider 请设 LLM_PROVIDER 等环境变量后重启");
            return;
        }
        String from = cur.id();
        ctx.switchModel(args);
        out.println("[mini-code] 已切换模型：" + from + " → " + args + "（provider " + cur.provider() + " 不变）");
    }

    /** /new：关旧会话、建新会话、清内存 history、重建压缩器；降级模式下为重试建会话。 */
    private static void newSession(String args, ReplContext ctx) {
        PrintStream out = ctx.out();
        try {
            ctx.newSession();
            out.println("[mini-code] 已开新会话：" + ctx.session().filePath());
        } catch (Exception e) {
            out.println("[mini-code] 新建会话失败：" + e.getMessage());
        }
    }

    /** /export [file]：会话 JSONL 复制到目标；无参默认 ./session-<时间戳>.jsonl；已存在不覆盖。 */
    private static void export(String args, ReplContext ctx) {
        PrintStream out = ctx.out();
        if (ctx.session() == null) {
            out.println("[mini-code] 导出不可用（降级模式：无持久化会话）");
            return;
        }
        Path target;
        if (args.isEmpty()) {
            String ts = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                    .withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.now());
            target = ctx.workdir().resolve("session-" + ts + ".jsonl");
        } else {
            target = Path.of(args);
            if (!target.isAbsolute()) {
                target = ctx.workdir().resolve(target);
            }
        }
        try {
            ctx.exportSession(target);
            out.println("[mini-code] 已导出会话 → " + target);
        } catch (java.nio.file.FileAlreadyExistsException e) {
            out.println("[mini-code] 导出失败：目标已存在 " + target + "（换个文件名重试）");
        } catch (Exception e) {
            out.println("[mini-code] 导出失败：" + e.getMessage());
        }
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
