---
status: accepted
---

# MVP2：工具钩子同步链 + ToolKind 分组并行

## Background

AgentLoop 工具执行是顺序 for（`AgentLoop.java:142` 注释预留「MVP2 再加并行」），且无拦截缝。`CONTEXT.md:38`/`AGENTS.md:60` 定 MVP2 = before/afterToolCall 钩子 + 并行；ADR-0002 裁定权限门禁走 beforeToolCall 钩子扩展、不内置弹窗。ADR-0002 同时把「AgentHarness hooks」列为不提前铺的空壳——本 ADR 即该空壳的落地决策：现在做，且只做已需要的形状。

## Decision

- **钩子**：`ToolHook` 接口（`beforeToolCall(ToolCallEvent) → ToolDecision`、`afterToolCall(ToolResultEvent)`），`ToolDecision` 三档 `PROCEED/BLOCK/MODIFY`；同步链、按注册顺序票决、BLOCK 短路；BLOCK/钩子异常 → `isError:true` 结果回 LLM（reason 作文本，对齐 pi wiki/19 L100）；MODIFY 替换 ToolCall，后续钩子看到修改后的（对齐 pi 可变引用语义）。`AgentLoop` 构造注入 `List<ToolHook>`，默认空 = 行为零变化。
- **并行**：`ToolDefinition.kind()` 默认 `STATEFUL`（fail-safe），`ReadTool` 显式 `READ_ONLY`；同 turn 多 tool_calls 按 LLM 顺序切连续段（runs），全 READ_ONLY 段并行、含 STATEFUL 段串行；结果严格按 LLM 发出顺序回收；daemon `FixedThreadPool(nProc)` 懒创建（首次 size>1）；并行组内任一异常 fail-fast cancel 兄弟，被取消者构造 isError 结果；BashTool 捕 `InterruptedException` 后 `destroyForcibly`。

为何选此：钩子是门禁/观测/改参的最小完整缝；ToolKind 分组在不引入依赖图的前提下保住文件系统副作用顺序；daemon 池免改 AgentLoop/Main 生命周期。

## Considered Options

- **async 钩子**：门禁/日志语义天然同步，async 徒增 API 面与测试复杂度。否。
- **BLOCK 带 terminate（终止整批，pi 同款）**：需要「提前结束回合」新分支；本轮 per-tool 独立票决已覆盖门禁需求。留后续。
- **全工具无差别并行**：write/edit 同文件竞态不可接受；依赖声明（谁挡谁）过度设计。ToolKind 两档是中间解。否两端。
- **parallelStream / 虚拟线程**：共享 ForkJoinPool 不利 REPL 长会话可预测；Java 17 无虚拟线程。否。
- **execute 协议加 signal（pi 同款）**：牵动 4 工具 + 全部工具测试；线程中断 + BashTool destroy 已覆盖取消需求。留后续。

## 有意偏离（对齐 pi X，但有意简化为 Y）

- pi 钩子有 `terminate`（整批提前结束回合）→ mini-code BLOCK 仅跳过该工具，兄弟照跑。
- pi `emitToolResult` 处理器可累积改写 content/details → mini-code afterToolCall 仅观测（void），异常只记日志。
- pi 钩子经扩展系统动态装卸（`/reload` 免重装）→ mini-code 构造注入 List，无 registry。
- pi `execute(toolCallId, params, signal, onUpdate, ctx)` 带 signal → mini-code execute 无 signal，取消靠 `Future.cancel(true)` 线程中断。
- pi 无 ToolKind 概念（并行策略在 harness 层）→ mini-code 在 Tool 协议上加 kind() 两档，自加的轻约束。

## Consequences

- 门禁类能力（危险 bash 拦截等）= 用户自写 BLOCK 钩子，路径打通（ADR-0002 裁定的落地位置）。
- 钩子实现者需自知 before/after 可能被并发调用（并行组内 per-tool 独立票决）——ToolHook javadoc 注明。
- 无钩子 + 单工具回合下行为与 MVP1 完全一致，229 既有测试零改动是全绿基线。
- 后续加项：terminate、afterToolCall 改写、registry/动态装卸、execute signal、依赖图并行。
