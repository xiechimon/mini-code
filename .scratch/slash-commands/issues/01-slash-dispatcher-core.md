# 01: 斜杠命令调度器内核 + 只读命令

**What to build:** 交互式 REPL 里敲 `/help`、`/session`、`/compact` 立即可用：`/help` 列出全部命令与说明；`/session` 显示会话文件路径、sessionId、消息数、估算 token 与距压缩阈值余量；`/compact` 无视阈值立即压缩并显示前后 token 对比。输入 `/c` 按 Tab 可补全命令名。`/quit` 与 `/exit` 一样退出。未识别的 `/xxx` 原样发给 LLM(fallthrough 终点语义)。命令轮不触发自动压缩。管道与 one-shot 模式不解析斜杠命令、原样透传。

**Blocked by:** None (can start immediately)

**Status:** resolved

- [ ] `SlashCommand` 函数式接口 + `SlashDispatcher`(首 token 字面量匹配,`Result { NOT_A_COMMAND, HANDLED, EXIT }`)+ `SlashCommands` 注册表(LinkedHashMap,注册序即 `/help` 序),对齐 pi `slash-commands.ts` 模块边界
- [ ] `ReplContext` 只读面:持有 workdir/llm/tools/systemPrompt/triggerSupplier/输出流 + 当前 loop/history/session/compactor 的读取访问
- [ ] JLine 循环与 Scanner 兜底循环各一处拦截:exit 检查后、回合派发前;`HANDLED` 跳过本轮 `onTurnComplete`
- [ ] `/help` 输出含全部已注册命令名与一行说明,样式走既有 Style/EventRenderer ANSI 常量
- [ ] `/session` 显示五项信息(文件路径/sessionId/消息数/token 估算/阈值余量);降级模式(session 为 null)给出不可用提示而非崩溃
- [ ] `/compact` 直调 ContextCompactor 压缩 + history 投影替换,输出压缩前后 token 对比
- [ ] Tab 补全:LineReaderBuilder 加 StringsCompleter,候选为命令表键集,只补首 token
- [ ] `isExitCommand` 增加 `/quit` 别名
- [ ] Prefactor:ContextCompactor 的 token 估算方法公开化;SessionManager 增加 `sessionId()` 只读访问
- [ ] 测试:SlashDispatcherTest(非斜杠行/未注册命令 → NOT_A_COMMAND;`/help` → HANDLED 含全部命令名;tokenization);`/compact` 用 fake LlmClient 固定摘要断言 history 收缩 + JSONL 出现 compaction 条目;命令输出经注入 PrintStream 断言
- [ ] 零回归:MainReplTest 既有用例不动 + `/quit` 断言;filterPipeLines 对 `/compact` 行原样保留
- [ ] `mvn test` 全绿
