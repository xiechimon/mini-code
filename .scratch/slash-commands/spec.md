# Spec: MVP4 斜杠命令 — REPL 交互命令层

Status: ready-for-agent

## Problem Statement

用户在交互式 REPL 里没有任何元命令可用：想清空上下文开新会话、想手动触发压缩、想看当前会话/模型状态、想换模型，都只能退出进程重来。当前仅 `/exit` 被特判识别（`isExitCommand`），`/help`、`/compact`、`/model` 等一律不识别——且未识别的 `/xxx` 行会被原样发给 LLM，行为未定义。pi（对齐标的）在交互模式有完整的斜杠命令层（wiki/4 :95–130：内置命令表 + 字面量匹配 + fallthrough），mini-code 这一层整体缺失。

## Solution

在 cli 层新增斜杠命令子系统，对齐 pi 的 `slash-commands.ts` 模块边界：注册表调度器 + 命令 = 会话上下文上的操作。REPL 输入在退出检查之后、发给 AgentLoop 之前先过调度器；命中则执行命令并跳过本轮 LLM 调用，未命中则按 pi fallthrough 终点语义原样发给 LLM。

v1 命令集（8 条 + Tab 补全）：

| 命令 | 行为 |
|---|---|
| `/help` | 列出全部命令与一行说明（注册序即展示序） |
| `/exit` `/quit` | 退出 REPL（既有路径，补 `/quit` 别名对齐 pi 命名） |
| `/compact` | 无视阈值立即压缩当前上下文，复用现有 ContextCompactor |
| `/model` | 无参显示当前 provider/model/baseUrl；带参在同 provider 内切换模型 id |
| `/session` | 会话文件路径、sessionId、内存消息数、估算 token、距压缩阈值余量 |
| `/new` | 关闭旧会话、建新会话、清空内存 history、重建压缩器 |
| `/export [file]` | 当前会话 JSONL 复制到目标路径（默认 `./session-<时间戳>.jsonl`） |
| Tab 补全 | JLine 行编辑时对 `/命令名` 提供补全 |

## User Stories

1. As a REPL 用户, I want 输入 `/help` 看到全部可用命令及说明, so that 不查文档也能发现功能
2. As a REPL 用户, I want `/exit` 和 `/quit` 都能退出, so that 与 pi 的肌肉记忆一致
3. As a REPL 用户, I want 输入 `/compact` 立即压缩上下文, so that 长会话中我能主动控制 token 规模而不等自动阈值
4. As a REPL 用户, I want `/compact` 后看到压缩前后 token 对比, so that 我知道压缩生效了多少
5. As a REPL 用户, I want 输入 `/model` 查看当前 provider/model/baseUrl, so that 确认自己在跟哪个模型说话
6. As a REPL 用户, I want `/model <id>` 在不重启进程的情况下切换模型, so that 同一会话里对比不同模型表现
7. As a REPL 用户, I want `/model` 切换后自动压缩仍指向新模型, so that 后续压缩摘要用的是正在对话的模型
8. As a REPL 用户, I want 输入 `/session` 看到会话文件、sessionId、消息数、token 估算与距压缩阈值余量, so that 了解当前会话状态
9. As a REPL 用户, I want 输入 `/new` 立即开一个全新会话, so that 不用退出进程就能切换任务上下文
10. As a REPL 用户, I want `/new` 后旧会话 JSONL 完整保留在磁盘, so that 历史不丢失
11. As a REPL 用户, I want 输入 `/export` 把当前会话 JSONL 复制到指定文件, so that 可以归档或分享会话记录
12. As a REPL 用户, I want `/export` 无参时使用默认文件名, so that 快速导出不用想名字
13. As a REPL 用户, I want 输入 `/c` 按 Tab 补全为 `/compact`, so that 少打字且能发现命令
14. As a REPL 用户, I want 未识别的 `/xxx` 原样发给 LLM, so that 行为与 pi 一致（命令不存在时它就是一个普通提示词）
15. As a 管道模式调用方, I want 管道输入中的 `/xxx` 行原样发给 LLM 不被拦截, so that 批处理脚本行为可预期（对齐 pi：斜杠命令是交互编辑器层概念）
16. As a REPL 用户, I want 执行斜杠命令的轮次不触发自动压缩检查, so that 无 LLM 回合的操作不会意外改变上下文
17. As a REPL 用户, I want 斜杠命令的输出有统一的终端样式, so that 与现有 banner/事件渲染观感一致
18. As a 降级模式用户（会话初始化失败）, I want `/export`、`/session` 明确提示不可用而非报错崩溃, so that 内存模式依然可用

## Implementation Decisions

- **新模块：cli 层斜杠命令子系统**，对齐 pi `slash-commands.ts` 的单模块命令表边界。四个类型：
  - `SlashCommand` — 函数式接口，`execute(String args, ReplContext ctx)`
  - `SlashDispatcher` — 调度器，返回 `Result { NOT_A_COMMAND, HANDLED, EXIT }`；首 token 精确字面量匹配（对齐 pi「逐字面量」），参数 = 首个空白后的余串
  - `SlashCommands` — 内置命令表工厂，`LinkedHashMap` 注册（注册序即 `/help` 展示序）
  - `ReplContext` — 会话级状态容器与命令操作面（见下）
- **Fallthrough 语义**：未匹配的 `/xxx` 返回 `NOT_A_COMMAND`，按原样作为 prompt 发给 LLM——这是 pi fallthrough 链（内置→扩展→技能→模板→普通提示词）的终点。v1 无扩展/技能/模板中间层，属有意简化，记入 ADR-0006。
- **拦截点**：JLine 循环与 Scanner 兜底循环各一处，位于退出检查之后、回合派发之前；`HANDLED` 时跳过本轮 `onTurnComplete`（无 LLM 回合不做自动压缩检查）。管道模式与 one-shot 模式不解析斜杠命令，原样透传。
- **ReplContext 职责**：不可变持有 workdir / llm / tools / systemPrompt / triggerSupplier / 输出流；可变持有（volatile）loop / history / session / compactor。暴露三个生命周期操作：
  - `switchModel(String id)` — 用同 provider/baseUrl/api 重建 Model 并 new AgentLoop 换入。`LlmClient.chat(model, ctx)` 本就每次传 model，与实例解耦，故切换无需动 AgentLoop 本体。**v1 限同 provider 内切换**，跨 provider 涉及 apiKey 重解析，留后续。
  - `newSession()` — close 旧 SessionManager → create 新 → 换 SessionHistory 与 ContextCompactor。
  - `exportSession(Path target)` — `Files.copy` 当前会话 JSONL；降级模式（session 为 null）返回不可用提示。
- **REPL 循环改读活引用**：每轮从 `ctx.currentLoop()` / `ctx.history()` 取当前实例；`buildTurnComplete` 改为经 ctx 读活引用，使 `/model`、`/new` 之后自动压缩钩子仍指向当前模型与会话。`runReplTurn` 两变体签名不动（爆炸半径已验证：无任何测试直调这两个循环）。
- **`/compact` 语义**：无视阈值立即执行压缩（区别于自动压缩的 `shouldCompact` 闸门），压缩后 history 走既有 `replaceKeepingInMemory` 投影。忽略 pi 的自定义压缩指令参数——ContextCompactor 的 summarize 缝目前无此入口，记入 ADR-0006。
- **`/quit` 别名**：并入既有 `isExitCommand` 特判（对齐 pi 命名），斜杠命令表不再重复注册退出命令。
- **Tab 补全**：JLine `LineReaderBuilder` 加 `StringsCompleter`，候选 = 命令表键集；只补首 token。
- **既有类的小开口**：`ContextCompactor` 的 token 估算方法公开化（供 `/session` 显示）；`SessionManager` 增加 `sessionId()` 只读访问。
- **命令输出样式**：统一走既有 `Style`/`EventRenderer` ANSI 常量，管道模式降级纯文本（`Style.detect` 既有逻辑）。
- **命令集命名对齐 pi**：探索清单里的 `/status` 并入 pi 对齐命名 `/session`。

## Testing Decisions

**测试缝（唯一一条）**：`SlashDispatcher.dispatch(String, ReplContext)` + 各命令作为 `ReplContext` 上的操作。命令输出经 ctx 注入的 `PrintStream` 断言，全程不需要终端。好测试的标准：只测外部行为（分发结果枚举、输出文本、history/JSONL 的可观测变化），不测内部分支。

- **SlashDispatcherTest**（纯分发表）：非斜杠行 → `NOT_A_COMMAND`；`/help` → `HANDLED` 且输出含全部命令名；未注册 `/xxx` → `NOT_A_COMMAND`（fallthrough）；大小写与带参 tokenization。
- **命令单测**：ctx 用内存 history + `@TempDir` 下 `SessionManager.create` + fake `LlmClient` lambda 构造（先例：`AgentLoopTest` 的 fake llm、`MainReplTest` 的 `@TempDir` + 纯函数断言风格）。
  - `/compact`：fake llm 返回固定摘要 → 断言 history 收缩 + JSONL 出现 compaction 条目（用 `SessionContext.load` 验证投影）。
  - `/model`：`switchModel` 后 `currentLoop()` 为新实例且 `/model` 显示新 id。
  - `/new`：新会话 JSONL 头落盘、history 清空、旧文件仍在。
  - `/export`：目标文件内容与会话文件一致；降级模式输出不可用提示。
- **零回归锁定**：`MainReplTest` 既有用例不动，仅 `isExitCommand` 增加 `/quit` 断言；`filterPipeLines` 对 `/compact` 行原样保留（管道透传锁定）。

## Out of Scope

- pi 的扩展注册命令 / 技能命令（`/skill:name`）/ 提示词模板中间层（fallthrough 只保留终点语义）
- 树形会话命令：`/tree` `/fork` `/clone` `/resume` `/import`（SessionManager 为线性 append-only，无 parentId/leaf，见 ADR-0003）
- `/share` `/thinking` `/login` `/logout` `/settings` `/copy` `/name` `/trust` `/reload` `/hotkeys` `/scoped-models` `/changelog`（缺 gist 上传、reasoning 参数、OAuth、设置系统、剪贴板等基础设施）
- 跨 provider 的 `/model` 切换；pi 式 TUI 选择器（无任何选择器组件）
- 回合进行中的消息队列（mini-code REPL 回合期间阻塞）
- 管道/one-shot 模式的斜杠命令解析（有意不透传以外行为）

## Further Notes

- 偏离记录汇总进 `docs/adr/0006-slash-commands.md`：无扩展/技能/模板 fallthrough 中间层；管道/one-shot 不解析；`/help` 为自加项；`/compact` 忽略自定义指令参数；`/model` 限同 provider。
- 实施拆两张票：`issues/01-slash-dispatcher-core.md`（调度器内核 + ReplContext 只读面 + `/help /session /compact` + Tab 补全 + `/quit` 别名）、`issues/02-model-switch-session-lifecycle.md`（ReplContext 可变面 + `/model /new /export` + buildTurnComplete 活引用重构）。01 blocks 02。
- 对齐参考：pi wiki/4（交互模式命令）、wiki/5（会话管理与压缩）；ADR-0002（六 No 共同省略）、ADR-0003（会话 JSONL）、ADR-0004（压缩）。
