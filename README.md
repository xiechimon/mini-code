# mini-code — Minimal Claude Code in Java

> 学习 Claude Code / Pi 底层原理的复刻项目：每个功能先跑通 MVP，再持续优化。

**对齐标的：** `earendil-works/pi` (TS, 100k stars) — pi 本身就是按「先 loop+工具跑通，再叠能力」做的，本项目用 Java 1:1
翻译其核心。

## 已完成 — MVP1

- **Agent Loop** — `AgentLoop.java` 对齐 `pi/packages/agent/src/agent-loop.ts`：
  `prompt → LLM → tool_calls → execute → loop`，支持 `length` 截断全量失败、`error/aborted` 终止，顺序执行（并行留到 MVP2）
- **Tools 4件套** — `read / write / edit / bash`，协议与截断（2000行/50KB）对齐 pi
- **LLM 接入** — `OpenAiCompatClient` 走 OpenAI-compat `/chat/completions`，`LlmConfig` 自动解析 `OPENCODE_API_KEY`（优先读
  `~/.local/share/opencode/auth.json`，无 key 时返回 error message 而非抛异常，符合 StreamFn 契约）
- **CLI** — `dev.minicode.cli.Main`：`java -jar mini-code.jar "prompt"`，systemPrompt 含 workdir，事件打印
- **验证** — 15 tests 绿 + 真实 LLM E2E：`opencode-go/kimi-k2.6` @ `https://opencode.ai/zen/go/v1` 完成
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

- [ ] **MVP2** Permission/Approve + `beforeToolCall`/`afterToolCall` 钩子 + tool 执行并行
- [ ] **MVP3** Streaming SSE + Context 压缩/裁剪 + Session 持久化 (JSONL)
- [ ] **MVP4** PlanMode / Todo + MCP + TUI

每 MVP 一个分支 → PR → code-review 后合 main。

## git 分支边界

- `main` — 最小 MVP1 内核 + `docs/wiki/`（Pi 学习笔记），当前规范主线。

## opencode 调研笔记

- Server `opencode serve` 默认 `127.0.0.1:4096`，OpenAPI `/doc`，SDK `@opencode-ai/sdk`，但 LLM 本身不走本地 server
- LLM 网关：`opencode` (Zen) `https://opencode.ai/zen/v1` / `opencode-go` `https://opencode.ai/zen/go/v1`，均用 `
  OPENCODE_API_KEY`，api 类型按模型分 `openai-completions`/`anthropic-messages` 等
- 本地 1.18.20 已装，`auth.json` 含 `opencode-go` key，tui.json 权限 allow
- mini-code 第一阶段直接调网关，不耦合本地 4096 server；后续可加 `OpenCodeServerClient` 实现同一 `LlmClient` 接口
