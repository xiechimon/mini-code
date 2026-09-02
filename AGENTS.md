# AGENTS.md — mini-code

> Java 复刻 Claude Code / Pi：每个功能先跑通 MVP，再持续优化。对齐标的 `earendil-works/pi`。

## 项目定位

- 单模块 Maven 起步，包名 `dev.minicode.*`，Java 17 / Maven 3.9 / Jackson / JUnit5+Mockito
- 分层对齐 pi：`ai (pi-ai)` → `agent (pi-agent-core/agent-loop.ts)` → `tools (pi-agent-core/harness/tools)` → `cli (pi-coding-agent)`
- 当前 MVP1 已完成：`AgentLoop + 4工具(read/write/edit/bash) + OpenAiCompatClient + CLI`，15 tests 绿 + 真实 LLM E2E 闭环

## 目录与职责

```
src/main/java/dev/minicode/
  ai/      — Model / Message / Context / Tool / LlmClient / OpenAiCompatClient / LlmConfig / Dotenv
  agent/   — AgentLoop / AgentEvent  (对齐 pi/packages/agent/src/agent-loop.ts)
  tools/   — ReadTool / WriteTool / EditTool / BashTool / ToolDefinition / ToolResult / Truncate / Workspace
  cli/     — Main  (systemPrompt 含 workdir)
  session/ — 预留 MVP3 (JSONL 持久化)
```

单模块先跑通再拆多模块，禁止过早拆分。

## Env 与 LLM 接入

- 解析优先级：`System.getenv() > .env(向上查找) > ~/.local/share/opencode/auth.json`，见 `LlmConfig.java` + `Dotenv.java`
- `LLM_PROVIDER/LLM_MODEL/LLM_BASE_URL/LLM_API_KEY` 显式覆盖；否则按 provider 查 `OPENCODE_API_KEY/DEEPSEEK_API_KEY/OPENAI_API_KEY/ANTHROPIC_API_KEY/MINIMAX_CN_API_KEY`
- `provider=opencode|opencode-go` 默认走网关 `https://opencode.ai/zen/go/v1`，可用 `OPENCODE_BASE_URL` 覆盖
- 默认模型：`opencode-go/kimi-k2.6`、`deepseek/deepseek-chat`、`openai/gpt-4o-mini`
- 无 key 时返回 error message 而非抛异常（符合 `LlmClient` StreamFn 契约）；HTTP 用 JDK `HttpClient`，不引入 OkHttp

## 核心约束（对齐 pi，不可擅自偏离）

1. **AgentLoop** 严格对齐 `pi/packages/agent/src/agent-loop.ts`：`prompt → LLM → tool_calls → execute → loop`，支持 `stopReason=end/length/error/aborted`，`length` 时全量工具调用视为失败并让模型重试，顺序执行（并行留到 MVP2）
2. **Tool 协议** 对齐 pi：`name + description + jsonSchema + execute(id, input, signal)`，返回 `content + details`，`isError` 标记失败
3. **截断策略**：Read `2000行 / 50KB` 先到先截断，与 pi 一致，见 `Truncate.java`
4. **消息模型**：`Context = systemPrompt + messages + tools`，`Message` 含 `content/toolCalls/toolResult`，`stopReason` 归一化
5. **事件**：通过 `AgentEvent + EventSink` 对外发射 `AgentStart/TurnStart/MessageEnd/ToolStart/ToolResultEvent/TurnEnd/AgentEnd`，便于 TUI 接入

## 编码规范

- Java 17，Maven 约束不变，新增依赖需说明理由，优先用 JDK/Jackson/SLF4J 已有能力
- 中文注释，类头说明对齐的 pi 源文件路径
- `edit` 用精确替换（`oldText` 唯一且最小），多处改动合并为一次 `edit` 调用；`write` 仅用于新文件或全量重写
- 日志用 `slf4j`，工具执行失败 `log.warn` 并返回 `isError=true` 的 `ToolResult`，不让循环崩溃
- 包内聚：`ai` 不依赖 `agent/tools`，`agent` 依赖 `ai+tools`，`cli` 组装所有
- 避免 `curl/WebSearch` 直连外网，公开内容检索走 `agent-reach`（`agent-reach doctor --json` 查可用渠道）

## 测试要求

- 新增/修改功能必须补单测，`mvn test` 全绿才算完成
- `AgentLoopTest` 覆盖 `end/length/error/aborted` 与工具分发；Tools 单测覆盖截断与边界；LLM 单测用 Mockito mock `HttpClient`
- 真实 E2E 仅在有 key 时跑：`opencode-go/kimi-k2.6` 验证 `read → write` 闭环

## MVP 节奏

| MVP | 内容 | 验收 |
|-----|------|------|
| MVP1 | AgentLoop + 4工具 + CLI 单句 | `mvn test` 绿 + 真实文件改动 |
| MVP2 | Permission/Approve + before/afterToolCall + 并行执行 | 危险操作需确认 |
| MVP3 | Streaming SSE + Context 压缩/裁剪 + Session JSONL | 长对话不爆 token |
| MVP4 | PlanMode/Todo + MCP + TUI | — |

每 MVP 单分支 → PR → `code-review` skill 双轴审查（Standards/Spec）后合 main。

## Git 协作

- 分支命名 `mvp2-permission` / `feat/xxx` / `fix/xxx`
- 提交信息简洁中文或英文均可，关联 MVP 目标
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
