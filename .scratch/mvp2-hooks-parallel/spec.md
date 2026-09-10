# Spec: MVP2 — before/afterToolCall 工具钩子 + 工具并行执行

Status: ready-for-agent

## Problem Statement

AgentLoop 的工具执行是顺序 for 循环（`AgentLoop.java:142` 注释原文「顺序执行工具（MVP），MVP2 再加并行」），且循环上没有任何拦截缝：无法在执行前阻断危险调用、无法改写参数、无法在执行后观测。`CONTEXT.md:38` 与 `AGENTS.md:60` 已把 MVP2 定为「before/afterToolCall 钩子 + tool 执行并行」，且工具门禁（权限类能力）按 ADR-0002 的裁定走 beforeToolCall 钩子扩展、不内置弹窗。

## Solution

两个 ticket，01 blocks 02（同触 AgentLoop 工具循环，先稳钩子缝再动并发）：

1. **工具钩子**（ticket 01）：`ToolHook` 接口 + `ToolDecision` 三档（PROCEED/BLOCK/MODIFY），AgentLoop 在每次工具执行前后走同步钩子链。BLOCK 短路该工具并构造 `isError:true` 结果回 LLM（reason 作文本，对齐 pi wiki/19 L100）；MODIFY 替换 ToolCall 后续钩子看到修改后的（对齐 pi 可变引用语义）。
2. **并行执行**（ticket 02）：`ToolKind`（READ_ONLY/STATEFUL，默认 STATEFUL fail-safe）；同 turn 多 tool_calls 时 READ_ONLY 连续段并行、STATEFUL 串行、结果按 LLM 发出顺序收集；daemon FixedThreadPool 懒创建。

## User Stories

1. 作为维护者，我希望工具执行前有同步钩子链，以便挂门禁/日志/参数改写而不改 AgentLoop 本体
2. 作为维护者，我希望 BLOCK 决定产生 isError 工具结果回 LLM（reason 可见），以便模型能理解被拒原因并调整
3. 作为维护者，我希望 MODIFY 能替换 ToolCall 且后续钩子看到修改后的值，以便链式改写与 pi 语义一致
4. 作为维护者，我希望钩子异常 = 该工具失败（isError 结果），以便钩子故障不产生半执行状态
5. 作为维护者，我希望无钩子注册时行为与现状完全一致，以便零回归
6. 作为终端用户，我希望同一轮的多个只读工具（如多个 read）并行执行，以便长回合更快
7. 作为终端用户，我希望写类工具（write/edit/bash）保持顺序执行，以便文件系统副作用可预期
8. 作为维护者，我希望并行结果按 LLM 发出顺序回收，以便工具结果与 tool_call_id 配对语义不变
9. 作为维护者，我希望 Ctrl-C 中止能取消 in-flight 并行工具且 bash 子进程被 destroy，以便无孤儿进程
10. 作为维护者，我希望未知/未来工具默认 STATEFUL（串行），以便 fail-safe

## Implementation Decisions

> 按「为何选此/为何不做」逐条记录（常驻要求）。详见 `docs/adr/0005`。

- **钩子三档 PROCEED/BLOCK/MODIFY，同步链**：对齐 pi wiki/19 的 block/改参/观测三类能力。为何不做 async：钩子语义（门禁/日志）天然同步，async 徒增 API 面。
- **BLOCK 只跳过该工具，不终止整批**（偏离 pi 的 terminate）：为何选此：MVP2 聚焦，per-tool 独立票决语义最简；为何不做 terminate：需要「整批提前结束回合」的新分支，留后续。
- **钩子异常 = 该工具失败结果**：为何选此：避免 input 已被前序钩子改写的半执行状态；与 pi「block → isError 结果回 LLM」同形。
- **afterToolCall 异常仅记日志**：工具已执行完，事后失败结果无意义；pi 的 emitToolResult 是累积覆盖，本轮只观测不改写。
- **ToolKind 默认 STATEFUL**：为何选此：fail-safe，未知工具不并行；为何不默认 READ_ONLY：新工具忘声明会产生文件系统竞态。
- **READ_ONLY 连续段并行、STATEFUL 串行、保持 LLM 相对顺序**：为何选此：[read, write, read] 中 write 后的 read 必须看到 write 结果，全量重排会改变语义；为何不做依赖图：过度设计。
- **daemon FixedThreadPool 懒创建，不实现 AutoCloseable**：为何选此：AgentLoop 现无 close 生命周期，daemon 线程与 `OpenAiCompatClient.doStream` 的 monitor 线程同模式，Main 零改动；为何不用 parallelStream：共享 ForkJoinPool 不利 REPL 长会话可预测性；为何不用虚拟线程：Java 17 无。
- **任一工具异常 → fail-fast 取消同组未完成者**：完成的结果保留、未完成的构造 isError「已取消」结果，仍按 LLM 顺序回收。
- **execute 无 signal 参数**（偏离 pi 的 `execute(id, params, signal, ...)`）：取消靠 `Future.cancel(true)` 线程中断 + BashTool 捕 InterruptedException 后 `destroyForcibly`。为何不加 signal：改 Tool 协议牵动 4 工具 + 全部测试，本轮不值。

## Testing Decisions

- **主缝 = AgentLoop + fake LlmClient**：现有 fake 模式（lambda + AtomicInteger 回合计数，先例 `AgentLoopTest.toolCallSequenceEditsFile`）扩展为**同 turn 双 tool_calls**（现有测试无此形态，需新造）。
- **并行性证明用 CountDownLatch**：两个 READ_ONLY 测试工具在 execute 内互相 await（超时 5s）——只有真并发才能通过；STATEFUL 串行用进入/退出顺序记录断言。不写 timing-based 断言（flaky）。
- **钩子用 RecordingHook fake**：记录 before/after 调用序列 + 可配置决定，跨测试复用。
- **零回归**：无钩子 + 单工具回合下所有既有测试（229 个）不改断言全绿。

## Out of Scope

- terminate（整批提前结束回合）——pi 有，本轮偏离省略
- afterToolCall 改写结果（content/details 覆盖）——本轮只观测
- 钩子 registry / 动态装卸 / `/reload`——直接 List 构造注入
- execute 协议加 signal 参数
- 权限弹窗 UI（ADR-0002 六 No 共同省略；门禁 = 用户自写 BLOCK 钩子）
- 跨 turn 并行、多模型对比执行

## Further Notes

- **对齐参照**：pi `agent-session.ts`/`agent-loop.ts` 钩子接线（wiki/19 L7、L92、L100、L104）；「无 UI 默认阻断」原则支持 BLOCK 语义的 fail-safe 取向。
- **事实基线**（探测报告 2026-09-10）：工具循环 `AgentLoop.java:143-169`；事件族 9 record（ToolStart L73 / ToolResultEvent L79，无 ToolEnd）；`ToolDefinition.execute(String callId, Map<String,Object> arguments)` 无 signal；4 工具无可变实例状态（Workspace 不可变）；`LlmClient` 单抽象方法可 lambda；Java 17、无 lombok。
