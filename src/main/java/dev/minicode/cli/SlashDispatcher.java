package dev.minicode.cli;

/**
 * 斜杠命令调度器：首 token 精确字面量匹配（大小写不敏感），参数 = 首个空白后的余串。
 * <p>
 * 对齐 pi 的 builtin literal-match（wiki/4）：内置表未命中的 {@code /xxx} 返回
 * {@link Result#NOT_A_COMMAND}，由调用方按 fallthrough 终点语义原样发给 LLM
 * （v1 无扩展/技能/模板中间层，偏离记录见 docs/adr/0006）。
 * </p>
 */
public final class SlashDispatcher {

    /** 分发结果。 */
    public enum Result {
        /** 非斜杠行或未注册的命令名——调用方按普通 prompt 处理。 */
        NOT_A_COMMAND,
        /** 已命中并执行。 */
        HANDLED,
        /** 保留：命令要求退出 REPL。v1 退出走 {@code Main.isExitCommand} 特判（管道截断共用），不经纪册。 */
        EXIT
    }

    private final java.util.Map<String, SlashCommands.Entry> commands;

    public SlashDispatcher(java.util.Map<String, SlashCommands.Entry> commands) {
        this.commands = commands == null ? java.util.Map.of() : commands;
    }

    /**
     * 分发一行已 trim 的输入。
     *
     * @param trimmedLine 已去除首尾空白的整行
     * @param ctx         REPL 会话上下文
     * @return 分发结果；未命中不抛异常
     */
    public Result dispatch(String trimmedLine, ReplContext ctx) throws Exception {
        if (trimmedLine == null || trimmedLine.length() < 2 || !trimmedLine.startsWith("/")) {
            return Result.NOT_A_COMMAND;
        }
        String body = trimmedLine.substring(1);
        int sp = -1;
        for (int i = 0; i < body.length(); i++) {
            if (Character.isWhitespace(body.charAt(i))) {
                sp = i;
                break;
            }
        }
        String name = (sp < 0 ? body : body.substring(0, sp)).toLowerCase(java.util.Locale.ROOT);
        String args = sp < 0 ? "" : body.substring(sp + 1).trim();
        SlashCommands.Entry entry = commands.get(name);
        if (entry == null) {
            return Result.NOT_A_COMMAND;
        }
        entry.command().execute(args, ctx);
        return Result.HANDLED;
    }

    /** 注册表只读视图（/help 与 Tab 补全共用同一份，保证展示与实际命令一致）。 */
    public java.util.Map<String, SlashCommands.Entry> commands() {
        return commands;
    }
}
