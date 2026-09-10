# CONTEXT.md — mini-code

## 目标

通过 Java 复刻 Claude Code / Pi 的核心原理，逐功能做 MVP 再迭代优化。学习产出 = 能跑的代码，不额外写原理文档（按需）。

## 技术栈

- Java 17 (Zulu 17.0.7), Maven 3.9.16
- 单模块 Maven 起步，包名 `dev.minicode.*`，内部分 `ai` / `agent` / `tools` / `cli`
- HTTP: JDK `java.net.http.HttpClient`（不引入 OkHttp，避免额外依赖）
- JSON: Jackson
- 测试: JUnit 5 + Mockito

## 对齐标的

- 参考实现：`earendil-works/pi` (100k stars, TS) — 本项目的 MVP 即 pi 的最小闭环
- pi 分层：`pi-ai` (统一 LLM API) → `pi-agent-core` (agent-loop + tools + session) → `pi-coding-agent` (CLI) → `pi-tui`
- mini-code 第一阶段仅复刻 `pi-ai` + `pi-agent-core` 的最小可用子集

## opencode 接入（已调研）

- opencode = 开源 coding agent，TUI + Server 架构，`opencode serve` 默认 `127.0.0.1:4096`，OpenAPI 位于 `/doc`，SDK
  `@opencode-ai/sdk`
- LLM 侧由 opencode 代理 75+ providers，走 AI SDK + Models.dev
- 两条接入 Zen (`opencode`) / Go (`opencode-go`)，均用 `OPENCODE_API_KEY`，网关地址 `https://opencode.ai/zen` /
  `https://opencode.ai/zen/go`，底层 api = `openai-completions` / `openai-responses` / `anthropic-messages`
- 本地已装 opencode 1.18.20，`~/.local/share/opencode/auth.json` 含 `opencode-go` key，`~/.config/opencode/opencode.json`
  权限 `allow`
- mini-code 侧：不走本地 4096 server（避免耦合），直接走网关 OpenAI-compat HTTP；`LlmClient` 接口抽象，默认
  `OPENCODE_API_KEY` + `OPENCODE_BASE_URL` 可覆盖，默认模型 `opencode-go/kimi-k2.6`（可配）。后续可加
  `OpenCode ServerClient` 实现同一接口

## MVP 切分

- MVP1 (当前): AgentLoop + 4工具 (Read/Write/Edit/Bash) + SystemPrompt + CLI 单句执行 `mini-code "xxx"`；验收 =
  真实文件改动 + `mvn test` 绿
- MVP2: before/afterToolCall 钩子 + tool 执行并行（权限弹窗为对齐 pi 六 No 的共同省略，见 ADR-0002；门禁如后续需要经 beforeToolCall 钩子作扩展，非内置）
- MVP3: Streaming SSE + Session 持久化 + Context 压缩/裁剪
- MVP4+: PlanMode/Todo、MCP、TUI

## 关键设计决策

- 单模块先跑通再拆多模块（避免过早拆分）
- Tool 协议对齐 pi: `name + description + jsonSchema + execute(id, input, signal)`，返回 `content: Text|Image` +
  `details`
- AgentLoop 对齐 `pi/packages/agent/src/agent-loop.ts`: `prompts → streamAssistantResponse → toolCalls → execute → loop`
  ，支持 `stopReason` (end/length/error/aborted)
- 截断策略：Read 工具 `2000行 / 50KB` 先到先截断，与 pi 一致
- 迭代节奏：直接提交合入 main，验证靠 mvn test 全绿
- 版本边界：`main` = 最小 MVP1 内核 + `docs/wiki/`

## 术语

- AgentLoop: LLM 调度循环
- Tool: 供模型调用的结构化能力
- StreamFn: `Model + Context → AssistantMessageEventStream` 的抽象，与 pi 同名
- Context: `systemPrompt + messages + tools` 三件套
- 输入编辑器 (Input Editor): REPL 中接收用户输入的行编辑层（JLine），只管输入，不渲染 agent 事件
- 事件渲染 (Event Rendering): AgentEvent → 终端文本的呈现层，独立于 AgentLoop 协议；本轮聚焦面
- 等待反馈 (Turn Feedback): 请求发出到响应返回期间的屏幕呈现；由流式增量与生成中断构成，不再依赖轮首占位行
- 流式增量 (Stream Delta): LLM 逐片段返回的文本增量（事件名 `MessageUpdate`）；累积进当前开放块，未完成块随增量**原位重绘**（正文可见增长），完成块一次定稿打印、不再重绘；结构性块（围栏/表格）闭合才成盒成表；超出视口退化追加
- 生成中断 (Generation Interrupt): 用户在流式期间触发的取消；已生成的 partial 保留（stopReason=aborted）并追加了中断标记，未执行的工具调用不执行
- 对齐 pi (Alignment as Reference): mini-code 以 pi 为参照，但对齐的是抽象、边界与语义（名称/形状），不机械复制类型或实现；与 pi 不同处作有意偏离并记录，见 `docs/adr/0002`
- 有意偏离 (Deliberate Deviation): mini-code 主动选择与 pi 不同且有明确理由的做法（如无 TUI → 流式用原位重绘渲染 markdown 块；增量简化为单一 MessageUpdate；Message 用单一类+Role）。每处需在 ADR/类头注明「对齐 pi X，但有意简化为 Y」
- 消息生命周期 (Message Lifecycle): 一条助手消息从开始到结束的三事件 `MessageStart / MessageUpdate / MessageEnd`，对齐 pi 的 `message_start/update/end`；增量走**单层** `MessageUpdate`（原 `StreamDelta` 改名，不拆 pi 的两层 delta）；`aborted` 作为 `MessageEnd` 的 stopReason 变体（中断仍是一段消息的结束，非额外事件）；`MessageEnd` 携带最终完整消息、语义不变
- 追加式会话树 (Append-only Session Tree): 会话持久化用追加式 JSONL 树——每条记录带 `id`/`parentId` 建树、`leaf` 指针定当前、branch/compact/resume 均为指针/新增操作、历史从不修改；记录 `id` 用 **8-hex 短 ID**（碰撞重试→UUID，对齐 pi）；会话标识为 **文件头独立 uuid**，与网关路由 id（`x-opencode-session`）分开；会话按 **cwd 分桶** 存 `sessions/--<cwd 编码>--/`；`leaf` 取**物理行序最后记录**。对齐 pi 的 SessionManager
- 持久化单元 (Persistence Unit): 一条「已完成消息」记录（含消息文本、stopReason、工具调用与结果）；`MessageUpdate` 是**瞬时展示增量、不落盘**，只落最终态
- 上下文压缩 (Context Compaction): 上下文超阈值时把「旧历史」折成一条结构化摘要（`<summary>` 消息），「最近段」原样保留；完整历史仍存 JSONL 树（append-only），**压缩仅改上下文视图、不删原文**。触发混合：阈值自动（`contextTokens > contextWindow − reserveTokens`）+ 手动命令 `/compact`；JSONL 落 `compaction` 条目（`summary / firstKeptEntryId / tokensBefore`），对齐 pi
- 摘要因 (Summarization Mutation): 压缩时对内存历史视图的替换操作；仅缩上下文窗口、不动 JSONL「会话文件」（source of truth），由此消除旧的 cap-50 直接丢弃
- 上下文预算 (Context Budget): 模型上下文窗口（`contextWindow`）、预留（`reserveTokens`）、保留段（`keepRecentTokens`）三元组，是触发的面值；通过 `CompactionConfig` 常量类配置（对齐 pi 默认 `reserve=16384 / keepRecent=20000`）
- 压缩摘要格式 (Compaction Summary Format): LLM 生成的结构化检查点文本——**对齐 pi 的 6 段检查点**（`Goal / Constraints / Progress / Key Decisions / Next Steps / Critical Context`）但**有意简化为 4 段**（`Goal / Progress / Key Decisions / Next`），落在 `<summary>` 消息里供上下文视图打头用
- 工具钩子 (Tool Hook): 工具调用前/后的同步拦截链，三档决定 `PROCEED / BLOCK / MODIFY`；BLOCK 短路该工具并以 isError 结果回 LLM（reason 可见），MODIFY 替换调用且后续钩子看到修改后的；钩子异常 = 该工具失败。对齐 pi 的 beforeToolCall/afterToolCall，有意简化：同步链、无 terminate、无 registry（见 `docs/adr/0005`）
- 工具种类 (Tool Kind): 工具副作用分级 `READ_ONLY / STATEFUL`，**默认 STATEFUL**（fail-safe：未知工具不并行）；READ_ONLY 可并行、STATEFUL 串行。mini-code 自加的轻约束（pi 无对应概念）
- 并行工具执行 (Parallel Tool Execution): 同 turn 多工具调用按 LLM 发出顺序切连续段，全 READ_ONLY 段并行、含 STATEFUL 段串行；结果严格按 LLM 顺序回收；任一失败 fail-fast 取消同组未完成者
- 正文渲染 (Markdown Rendering): 助手消息正文的 Markdown→终端文本呈现，事件渲染的子层；颜色仍只标角色，降级规则与事件渲染同源
- 纯函数渲染缝: 渲染器只吃输入（文本/事件/样式/宽度）出文本，不读环境不碰时钟，单测断言输出字符串
- 斜杠命令 (Slash Command): REPL 输入行首为 `/` 的元命令，命中注册表则执行并跳过本轮 LLM 调用；未命中按 fallthrough 终点语义原样发给模型。退出命令（/exit /quit）走 `isExitCommand` 特判、不入注册表（与管道截断共用一条路径）。见 `docs/adr/0006`
- 命令注册表 (Command Registry): `SlashCommands.builtins()` 的 LinkedHashMap，注册序即 `/help` 展示序；`/help` 与 Tab 补全共用同一份数据源，保证展示与实际命令不漂移
- 命令自动提示 (Command Autosuggestion): 输入 `/` 即浮现可用命令列表、随输入实时过滤的交互层（JLine `SuggestionType.COMPLETER`），候选带一行说明；对齐 pi「输 / 即出列表」，不靠 Tab。Tab 补全共存：Tab 进入方向键菜单
