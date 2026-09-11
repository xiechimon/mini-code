# mini-code — Minimal Claude Code in Java

> 学习 Claude Code / Pi 底层原理的复刻项目：每个功能先跑通 MVP，再持续优化。

**对齐标的：** MVP1~4 对齐 `earendil-works/pi` (TS, 100k stars) 的核心抽象（抽象名/边界/语义，有意简化与偏离见 `docs/adr/0002`）；MVP5 起语义标的切换为 Claude Code 的内置 MCP / Skill / PlanMode（见 `docs/adr/0007`）。

## 已完成 — MVP1 ~ MVP4(斜杠命令)

- **Agent Loop** — `AgentLoop.java` 对齐 `pi/packages/agent/src/agent-loop.ts`：
  `prompt → LLM → tool_calls → execute → loop`，支持 `length` 截断全量失败、`error/aborted` 终止
- **Tools 4件套** — `read / write / edit / bash`，协议与截断（2000行/50KB）对齐 pi
- **LLM 接入** — `OpenAiCompatClient` 走 OpenAI-compat `/chat/completions`，`LlmConfig` 自动解析 `OPENCODE_API_KEY`（优先读
  `~/.local/share/opencode/auth.json`，无 key 时返回 error message 而非抛异常，符合 StreamFn 契约）
- **CLI** — `dev.minicode.cli.Main`：`java -jar mini-code.jar "prompt"`，systemPrompt 含 workdir，事件打印
- **MVP2 钩子 + 并行** — `ToolHook` before/afterToolCall 三档 ToolDecision（PROCEED/BLOCK/MODIFY）；
  同回合连续 READ_ONLY 工具并行、STATEFUL 串行，fail-fast 取消兄弟（ADR-0005）
- **MVP3 流式 + 会话** — SSE 流式渲染（Markdown/ANSI）、append-only JSONL 会话持久化、token 估算驱动的 Context 压缩（ADR-0003/0004）
- **MVP4 斜杠命令** — REPL 内 `/help` `/session` `/compact` `/model [id]` `/new` `/export [file]` +
  Tab 补全；注册表调度器 + ReplContext 操作面对齐 pi slash-commands.ts，未识别 `/xxx` 原样发 LLM（ADR-0006）
- **命令面板** — 输 `/` 呼出状态栏面板：↑↓ 选择、实时过滤、Enter 分流（无参即执行/带参填入）、Esc 只关面板；
  纯状态机 + widget 壳两层（TailTipWidgets 同款公开 API），JLine 3.30.9（ADR-0008）
- **诊断** — `--doctor` 一条命令打印解析后的 provider/model/baseUrl、key 来源层与掩码值、网关连通性（key 排障入口）
- **验证** — 290 tests 绿（真实网关测试 `@Tag("gateway")` 默认排除，`mvn test -Pgateway` 显式跑）+ 真实 LLM E2E：
  `read test.txt → write hello.txt` 三轮闭环

```
[mini-code] provider=opencode-go model=kimi-k2.6 baseUrl=https://opencode.ai/zen/go/v1
[turn 1] → read {"path":"test.txt"} ← Read ... init
[turn 2] → write {"path":"hello.txt","content":"hello mini-code"} ← Wrote 15 chars
[turn 3] Done.
```

## 快速开始

```bash
mvn package -DskipTests
java -jar target/mini-code-0.1.0-SNAPSHOT.jar "帮我把 README.md 的标题改成 Hello mini-code"

# 诊断：解析后的 provider/model/key 来源 + 网关连通性（key 排障一条命令）
java -jar target/mini-code-0.1.0-SNAPSHOT.jar --doctor

# 指定 provider/model
LLM_PROVIDER=deepseek LLM_MODEL=deepseek-chat DEEPSEEK_API_KEY=sk-... java -jar target/mini-code-0.1.0-SNAPSHOT.jar "xxx"
LLM_PROVIDER=openai LLM_MODEL=gpt-4o-mini OPENAI_API_KEY=sk-... java -jar ...
```

Env 解析优先级：`LLM_*` > `OPENCODE_API_KEY`/`DEEPSEEK_API_KEY`/`OPENAI_API_KEY` > `~/.local/share/opencode/auth.json`。

## 架构（对齐 pi）

```
dev.minicode.ai   — Model / Message / Context / Tool / LlmClient / OpenAiCompatClient / LlmConfig  (pi-ai)
dev.minicode.agent — AgentLoop / AgentEvent                                                         (pi-agent-core/agent-loop.ts)
dev.minicode.tools — ReadTool / WriteTool / EditTool / BashTool                                    (pi-agent-core/harness/tools)
dev.minicode.cli   — Main                                                                            (pi-coding-agent)
```

## 下一步 — 按功能 MVP 迭代

- [x] **MVP2** `beforeToolCall`/`afterToolCall` 钩子 + tool 执行并行
- [x] **MVP3** Streaming SSE + Context 压缩/裁剪 + Session 持久化 (JSONL)
- [x] **MVP4** 斜杠命令（`/help` `/session` `/compact` `/model` `/new` `/export`）
- [ ] **MVP5** PlanMode（`/plan` + `ExitPlanMode` 工具 + STATEFUL 工具拦截）
- [ ] **MVP6** Skill（SKILL.md 渐进披露 + `Skill` 工具 / `/skill-name` 双入口）
- [ ] **MVP7** MCP client（stdio + tools，配置对齐 `.mcp.json`）

直接提交合入 main，改动验证靠 mvn test 全绿。

## 开发流

- 直接提交合入 `main`（改动验证靠 `mvn test` 全绿）；`main` 承载最小 MVP 内核 + `docs/wiki/`（Pi 学习笔记）。

## opencode 调研笔记

- Server `opencode serve` 默认 `127.0.0.1:4096`，OpenAPI `/doc`，SDK `@opencode-ai/sdk`，但 LLM 本身不走本地 server
- LLM 网关：`opencode` (Zen) `https://opencode.ai/zen/v1` / `opencode-go` `https://opencode.ai/zen/go/v1`，均用 `
  OPENCODE_API_KEY`，api 类型按模型分 `openai-completions`/`anthropic-messages` 等
- 本地 1.18.20 已装，`auth.json` 含 `opencode-go` key，tui.json 权限 allow
- mini-code 第一阶段直接调网关，不耦合本地 4096 server；后续可加 `OpenCodeServerClient` 实现同一 `LlmClient` 接口
