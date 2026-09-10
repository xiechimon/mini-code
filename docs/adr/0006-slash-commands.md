---
status: accepted
---

# MVP4 斜杠命令：注册表调度器 + ReplContext 会话操作面

## Background

REPL 交互层只有 `/exit` 特判（`isExitCommand`），无元命令能力：不能手动压缩、不能看会话状态、不能换模型、不能开新会话。pi 在交互模式有完整斜杠命令层（wiki/4：内置命令表 + onSubmit 字面量匹配 + fallthrough 到扩展/技能/模板/普通提示词）。规格与票：`.scratch/slash-commands/`。

## Decision

- **注册表调度器**：`SlashCommand`（函数式接口）+ `SlashDispatcher`（首 token 字面量匹配、大小写不敏感，`Result { NOT_A_COMMAND, HANDLED, EXIT }`）+ `SlashCommands.builtins()`（LinkedHashMap，注册序即 `/help` 展示序），对齐 pi `slash-commands.ts` 的单模块命令表边界。
- **ReplContext**：会话级状态容器与命令操作面。不可变依赖构造注入（workdir/llm/tools/systemPrompt/triggerSupplier/maxTurns/sessionsBaseDir/out/style）；可变状态 volatile 引用（loop/model/history/session/compactor），生命周期命令换入新实例，REPL 循环与压缩钩子每次经 getter 取活引用。
- **拦截点**：JLine 与 Scanner 两循环各一处，exit 检查后、回合派发前；`HANDLED` 跳过本轮 LLM 调用与 onTurnComplete（无 LLM 回合不触发自动压缩）。
- **`/model <id>`**：`LlmClient.chat(model, ctx)` 本就与实例解耦，切换 = 同 provider/baseUrl/api 重建 Model + new AgentLoop 换入，不动 AgentLoop 本体。
- **Tab 补全**：JLine `StringsCompleter` 仅作用首 token，候选与注册表同一份数据源。

为何选此：注册表与 ReplContext 是同一条缝——既是命令操作面又是测试缝（命令 = ctx 上的纯操作，PrintStream 注入断言，全程无终端）；活引用让 `/model`、`/new` 后自动压缩钩子不漂移。

## Considered Options

- **Main 内 if-chain 派发**：Main 已近 700 行，命令增长线性恶化且无测试缝，与 pi 模块边界不同构。否。
- **REPL 实例类化替代 ReplContext**：搬迁约 300 行静态方法，收益同 ReplContext 但 diff 大数倍。否。
- **`/exit` 注册进命令表**：管道截断（`filterPipeLines`）与交互循环共用 `isExitCommand` 特判，入表会劈成两条退出路径。否（表中不注册，但 `/help` 与补全仍列出）。
- **管道/one-shot 也解析斜杠命令**：pi 中斜杠命令是交互编辑器层概念，print/管道模式无此层；批处理脚本里的 `/xxx` 应原样发给 LLM。否（透传，测试锁定）。

## 有意偏离（对齐 pi X，但有意简化为 Y）

- pi fallthrough 链含扩展注册命令 → 技能命令（`/skill:name`）→ 模板展开三层 → mini-code 只保留终点语义：内置表未命中即原样发 LLM。
- pi `/compact [prompt]` 支持自定义压缩指令 → mini-code 忽略参数（ContextCompactor 的 summarize 缝无此入口）。
- pi `/model` 是 TUI 选择器 + 跨 provider → mini-code 无选择器组件，`/model <id>` 限同 provider 内切 id（跨 provider 涉及 apiKey 重解析）。
- pi 无 `/help` 斜杠命令（靠补全发现）→ mini-code 自加 `/help`：注册表驱动的一行说明列表。
- `/export` 目标已存在时 pi 行为未对齐考察 → mini-code 选择拒绝覆盖并提示换名（不静默覆盖）。

## Consequences

- 加新命令 = 一处注册 + 一个 ctx 上的纯函数，`/help` 与 Tab 补全自动跟随。
- 命令轮不触发自动压缩；`/compact` 自身手动压缩不受此限。
- 降级模式（会话初始化失败）下 `/session` `/export` 明确提示不可用，`/new` 视为重试建会话。
- 后续加项：扩展/技能 fallthrough 中间层、`/compact` 自定义指令、跨 provider `/model`、TUI 选择器。
