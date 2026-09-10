# 02: 生命周期命令 — /model 切换、/new、/export

**What to build:** REPL 里敲 `/model` 显示当前 provider/model/baseUrl;`/model <id>` 不重启进程即在同 provider 内切换模型,且后续自动压缩用新模型;`/new` 立即开全新会话(旧会话 JSONL 完整留盘、内存 history 清空、压缩器重建);`/export [file]` 把当前会话 JSONL 复制到目标路径(默认 `./session-<时间戳>.jsonl`),降级模式提示不可用。

**Blocked by:** 01(斜杠命令调度器内核 + 只读命令)—— 本票在 ReplContext 上扩可变面,复用 01 的调度器与命令注册缝

**Status:** resolved（/export 目标已存在时的取舍：报错提示换名，不静默覆盖）

- [ ] ReplContext 可变面:`switchModel(id)`(同 provider/baseUrl/api 重建 Model + 新 AgentLoop 换入,不动 AgentLoop 本体)、`newSession()`(close 旧 → create 新 → 换 SessionHistory 与 ContextCompactor)、`exportSession(target)`(Files.copy;session 为 null 返回不可用提示)
- [ ] REPL 两循环每轮从 `ctx.currentLoop()` / `ctx.history()` 取活引用;`runReplTurn` 两变体签名不动
- [ ] `buildTurnComplete` 改为经 ctx 读活引用,`/model`、`/new` 后自动压缩钩子指向当前模型与会话
- [ ] `/model` 无参显示 provider/model/baseUrl;带参切换并输出新模型;跨 provider 输入明确拒绝并提示 v1 不支持
- [ ] `/new` 后旧会话文件仍存在磁盘、新 JSONL 头落盘、内存 history 为空
- [ ] `/export` 无参用默认文件名;目标已存在时不静默覆盖(报错或自动改名,实现时任选并在票里注明)
- [ ] `/help` 自动包含三条新命令(注册表驱动,无需改 /help 本体)
- [ ] 测试:`switchModel` 后 `currentLoop()` 为新实例且 `/model` 输出新 id;`/new` 后 history 清空 + 新会话文件落盘;`/export` 目标内容与会话文件一致;降级模式不可用提示
- [ ] `mvn test` 全绿
- [ ] 收尾:写 `docs/adr/0006-slash-commands.md` 记录偏离(无扩展/技能/模板 fallthrough 中间层;管道/one-shot 不解析;`/help` 为自加项;`/compact` 忽略自定义指令参数;`/model` 限同 provider);README「已完成」补斜杠命令小节
