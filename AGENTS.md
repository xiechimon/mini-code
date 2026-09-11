# AGENTS.md — mini-code

> Java 复刻 Claude Code / Pi：每个功能先跑通 MVP，再持续优化。对齐标的 `earendil-works/pi`。

## 项目定位

- 单模块 Maven 起步，包名 `dev.minicode.*`，Java 17 / Maven 3.9 / Jackson / JUnit5+Mockito
- 分层对齐 pi：`ai (pi-ai)` → `agent (pi-agent-core/agent-loop.ts)` → `tools (pi-agent-core/harness/tools)` → `cli (pi-coding-agent)`；`session` 为 JSONL 持久化+压缩
- 完成进度与当前状态的唯一真相源：README「已完成」节（本文件与 CONTEXT.md 不复述进度）

## 目录与职责

包结构以 `src/main/java/dev/minicode/` 实际目录为准（ai / agent / tools / cli / session 五包，类清单看代码即得）。单模块先跑通再拆多模块，禁止过早拆分。

## Env 与 LLM 接入

- 解析优先级：`System.getenv() > .env(向上查找) > ~/.local/share/opencode/auth.json`，见 `LlmConfig.java` + `Dotenv.java`
- `LLM_PROVIDER/LLM_MODEL/LLM_BASE_URL/LLM_API_KEY` 显式覆盖；否则按 provider 查 `OPENCODE_API_KEY/DEEPSEEK_API_KEY/OPENAI_API_KEY/ANTHROPIC_API_KEY/MINIMAX_CN_API_KEY`
- `provider=opencode|opencode-go` 默认走网关 `https://opencode.ai/zen/go/v1`，可用 `OPENCODE_BASE_URL` 覆盖
- 默认模型：`opencode-go/kimi-k2.6`、`deepseek/deepseek-chat`、`openai/gpt-4o-mini`
- 无 key 时返回 error message 而非抛异常（符合 `LlmClient` StreamFn 契约）；HTTP 用 JDK `HttpClient`，不引入 OkHttp

## 核心约束（对齐 pi，不可擅自偏离）

1. **AgentLoop** 严格对齐 `pi/packages/agent/src/agent-loop.ts`：`prompt → LLM → tool_calls → execute → loop`，支持 `stopReason=end/length/error/aborted`，`length` 时全量工具调用视为失败并让模型重试；工具执行按 ToolKind 分组（连续 READ_ONLY 段并行、STATEFUL 串行，见 ADR-0005）
2. **Tool 协议** 对齐 pi：`name + description + jsonSchema + execute(id, input, signal)`，返回 `content + details`，`isError` 标记失败
3. **截断策略**：Read `2000行 / 50KB` 先到先截断，与 pi 一致，见 `Truncate.java`
4. **消息模型**：`Context = systemPrompt + messages + tools`，`Message` 含 `content/toolCalls/toolResult`，`stopReason` 归一化
5. **事件**：通过 `AgentEvent + EventSink` 对外发射 `AgentStart/TurnStart/MessageStart/MessageUpdate/MessageEnd/ToolStart/ToolResultEvent/TurnEnd/AgentEnd`（消息生命周期 start/update/end），便于 TUI 接入

## 编码规范

- Java 17，Maven 约束不变，新增依赖需说明理由，优先用 JDK/Jackson/SLF4J 已有能力
- 中文注释，类头 Javadoc 第一段必须包含 `对齐 \`pi/<package>/<file>\`：<一句话职责>`，缺它即 review 不通过
- `edit` 用精确替换（`oldText` 唯一且最小），多处改动合并为一次 `edit` 调用；`write` 仅用于新文件或全量重写
- 日志用 `slf4j`，工具执行失败 `log.warn` 并返回 `isError=true` 的 `ToolResult`，不让循环崩溃
- 包内聚：`ai` 不依赖 `agent/tools`，`agent` 依赖 `ai+tools`，`cli` 组装所有；新类优先顶级，禁嵌套进已有类（历史兼容除外）

## 关键链路指针

- **回合取消机制（InterruptTrigger）**：契约在 `dev.minicode.agent.InterruptTrigger` 接口 Javadoc；生命周期 `AgentLoop.acquireTrigger` / `closeQuietly` 每回合一取一放；CLI 实现 `dev.minicode.cli.{SigInt,Terminal}InterruptTrigger`。改这条链前先看这三处，缺一会写出双重注册或 chain leak。

## 测试要求

- 新增/修改功能必须补单测，`mvn test` 全绿才算完成
- `AgentLoopTest` 覆盖 `end/length/error/aborted` 与工具分发；Tools 单测覆盖截断与边界；LLM 单测用 Mockito mock `HttpClient`
- 真实网关测试标 `@Tag("gateway")`，默认排除（`mvn test` 确定性本地绿）；`mvn test -Pgateway` 显式跑（需网络与有效 key）

## MVP 节奏

MVP 切分定义见 `CONTEXT.md`「MVP 切分」，各 MVP 完成状态见 README「已完成」——本文件不维护进度表。

## Git 协作

- 直接在 main 上合入，不另开 feature 分支；验证靠 `mvn test` 全绿
- staging 用明确路径逐个 add 本任务的文件；提交前 `git status` 核对每个待提交文件的归属（本仓库可能有多会话并行，工作区里会有别人的 WIP）
- 禁止提交 `.env`、`target/`、`.idea/`（已在 `.gitignore`）

## 常见陷阱

- `LlmConfig` 的 provider 映射改一处要同步改 `apiKeyForProvider`，避免两张表不一致
- `auth.json` 结构兼容 `string | {key} | {apiKey}`，用 Jackson 解析不用字符串截取
- `OPENCODE_BASE_URL` 仅对 `opencode*` provider 生效
- Tool 入参 `arguments` 可能为 null，需 `Map.of()` 兜底

## Skill 路由

- 报告 bug/异常 → `diagnosing-bugs` 先诊断再修复
- 改动审查 → `code-review`（传 `since=main` 或分支名）
- 需要联网调研 → `agent-reach`，需要浏览器交互/截图 → `ego-browser`
- 需求拆解为 agent 可执行任务书 → `leader`

## Agent skills

### Issue tracker

Issue 以本地 markdown 文件存于 `.scratch/<feature-slug>/`。See `docs/agents/issue-tracker.md`.

### Triage labels

默认五角色标签，标签串=角色名（needs-triage/needs-info/ready-for-agent/ready-for-human/wontfix）。See `docs/agents/triage-labels.md`.

### Domain docs

单上下文：根目录 `CONTEXT.md` + `docs/adr/`。See `docs/agents/domain.md`.
