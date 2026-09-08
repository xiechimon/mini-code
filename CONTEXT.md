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
- MVP2: Permission/Approve + before/afterToolCall 钩子
- MVP3: Context 压缩/Streaming SSE + Session 持久化
- MVP4+: PlanMode/Todo、MCP、TUI

## 关键设计决策

- 单模块先跑通再拆多模块（避免过早拆分）
- Tool 协议对齐 pi: `name + description + jsonSchema + execute(id, input, signal)`，返回 `content: Text|Image` +
  `details`
- AgentLoop 对齐 `pi/packages/agent/src/agent-loop.ts`: `prompts → streamAssistantResponse → toolCalls → execute → loop`
  ，支持 `stopReason` (end/length/error/aborted)
- 截断策略：Read 工具 `2000行 / 50KB` 先到先截断，与 pi 一致
- 迭代节奏：每 MVP 一个分支 → PR → code-review 后合 main
- 版本边界：`main` = 最小 MVP1 内核 + `docs/wiki/`；早期更模块化的快照见 `archive/archify-deep-mod`（43 源文件 + 完整 docs 树）

## 术语

- AgentLoop: LLM 调度循环
- Tool: 供模型调用的结构化能力
- StreamFn: `Model + Context → AssistantMessageEventStream` 的抽象，与 pi 同名
- Context: `systemPrompt + messages + tools` 三件套
- 输入编辑器 (Input Editor): REPL 中接收用户输入的行编辑层（JLine），只管输入，不渲染 agent 事件
- 事件渲染 (Event Rendering): AgentEvent → 终端文本的呈现层，独立于 AgentLoop 协议；本轮聚焦面
- 等待反馈 (Turn Feedback): 请求发出到响应返回期间的屏幕呈现；本轮范围外，streaming 归 MVP3
- 正文渲染 (Markdown Rendering): 助手消息正文的 Markdown→终端文本呈现，事件渲染的子层；颜色仍只标角色，降级规则与事件渲染同源
- 纯函数渲染缝: 渲染器只吃输入（文本/事件/样式/宽度）出文本，不读环境不碰时钟，单测断言输出字符串
