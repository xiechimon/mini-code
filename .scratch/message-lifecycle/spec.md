# Spec: Message Lifecycle（事件层对齐 pi 的 message_start/update/end）

Status: ready-for-agent

## Problem Statement

事件层目前把一条助手消息的生命周期压成「`StreamDelta` + `MessageEnd`」两个名字，**与 pi 的三事件生命周期不对齐**：没有显式的 `MessageStart`，读者看不出一条消息从哪里开始；`StreamDelta`/`MessageEnd` 的命名与已定的「对齐 pi 抽象」（`docs/adr/0002`）不一致——它用的是 mini-code 自己的词汇，而非 pi 的 `message_start/update/end`。这既是理解 pi 循环结构的缺口，也让「对齐 pi」停留在口号、没落到已落地的事件层。

## Solution

把事件层对齐成 pi 的**消息生命周期**三事件，并换掉旧命名：

- `MessageStart`：一条助手消息开始（消息开始前的标记/锚点事件，暂不携带消息元数据——`Message` 尚无 id）；用于渲染层刷新流式状态。
- `MessageUpdate`：逐片段文本增量（原 `StreamDelta` **改名**，语义不变，单层 delta）。
- `MessageEnd`：携带最终完整 `Message` 与其 `stopReason`（`aborted` 是 `stopReason` 的一种，非额外事件）。

`AgentLoop` 按 `MessageStart → MessageUpdate… → MessageEnd` 发射；渲染订阅层（`BlockStreamer`/`Main`）按这个生命周期挂接——`MessageUpdate` 喂增量、`MessageEnd` 触发定稿/中断标记。命名与边界靠齐 pi 的 message lifecycle，而**渲染机制本身**（原位重绘 + 块级定稿）是有意偏离，按 `docs/adr/0002` 保留并记录。

## User Stories

1. 作为维护者，我希望事件层有显式 `MessageStart`，以便能看出「一条消息在这里开始」而非只有增量与结尾
2. 作为维护者，我希望 `StreamDelta` 改名为 `MessageUpdate`，以便事件名靠齐 pi 的 message_update、无需另学一套词汇
3. 作为维护者，我希望 `MessageEnd` 仍是「该消息结束」的唯一终点，以便一条消息的生命周期自洽
4. 作为维护者，我希望 `aborted` 作为 `MessageEnd.stopReason` 变体、而非独立事件，以便中断语义与 pi 的 stopReason 家族一致、渲染层无需多分支
5. 作为维护者，我希望 `AgentLoop` 对每个助手消息发射 `Start/Update…/End`，以便 loop 的推进清晰可观测
6. 作为维护者，我希望渲染订阅层按生命周期挂接（Update 喂增量、End 触发定稿），以便事件名与渲染链路一致、不脱节
7. 作为终端用户，我希望流式期间正文仍逐条**可见增长**、块完成定稿、无重复、无残留，以便对齐事件名不破坏既有体验
8. 作为终端用户，我希望中断（Ctrl-C）仍保留 partial 并标记「⏹ 已中断」、进程不退出，以便中断语义不回归
9. 作为维护者，我希望 `MessageStart/MessageUpdate/MessageEnd` 是密封事件族的一员，以便穷举/单测可枚举
10. 作为维护者，我希望旧的 `StreamDelta` 命名被移除（不保留双轨），以便事件层单一、不两套并存
11. 作为维护者，我希望`消息生命周期`术语同步进 `CONTEXT.md`（含改名 + 后端），以便领域词汇与代码一致
12. 作为维护者，我希望对齐是**只对齐抽象名/边界/语义**（`MessageStart/Update/End`）、不引入 pi 的两层 delta，以便遵守 ADR-0002「不机械复制实现」

## Implementation Decisions

> 按「为何选此/为何不做」逐条记录（常驻要求）。

- **事件族升级**：`AgentEvent` 密封接口新增 `MessageStart`、把 `StreamDelta` 更名 `MessageUpdate`，保留 `MessageEnd`（语义不变：携带最终完整 Message + stopReason）。`AgentLoop` 发射顺序改为 `MessageStart → MessageUpdate… → MessageEnd`。为何选此：三事件在语义与命名上靠齐 pi 的 message lifecycle，理解价值最大且 churn 可控（`MessageUpdate` 就是原 `StreamDelta` 的同义改名）。为何不做两层 delta（pi 的 text/thinking/toolcall `*_delta` + agent `message_update` partial）：那是 pi-ai 的协议细节，mini-code 用 OpenAI-compat 单层增量足够，且无 thinking 流——引入只会增加事件家族与测试面，理解收益低。
- **打破旧命名、不保留双轨**：`StreamDelta` / `MessageEnd` 旧名随重构移除（`MessageEnd` 概念保留、只是成为生命周期一员；`StreamDelta` 更名 `MessageUpdate`）。为何选此：单一事件族，避免 AgentLoop 与渲染挂接为「新旧两套」各分支。为何不做兼容双轨：两套事件让 `AgentLoop`/`BlockStreamer`/`Main` 都要 `instanceof` 分流，理解价值为零。
- **中断 = `MessageEnd.stopReason=aborted`**：中断时仍发一条 `MessageEnd`，其 `stopReason=aborted`；渲染层据此打「⏹ 已中断」，`partial` 保留。为何选此：与既有语义一致（中断=一段消息的结束），`aborted` 本属 pi 的 stopReason 家族。为何不做专门中断事件：多一种事件类型，但语义上无需区分「aborted 的 MessageEnd」与「正常 MessageEnd」——渲染层看 stopReason 即可。
- **渲染订阅层对齐**：`Main` 的流式 sink 与 `BlockStreamer` 改为按生命周期挂接——`MessageStart` 标记消息开始（如重置流式状态），`MessageUpdate` 喂 `delta`，`MessageEnd` 触发 `flush(stopReason==aborted)`。为何选此：事件名靠齐后渲染链路一并对齐，避免事件名与渲染脱节。为何不做「只改事件层不动渲染」：事件名与挂接不同步会让后续维护者困惑；既然改事件族，渲染挂接一处对齐更彻底。
- **渲染机制不变（有意偏离）**：`BlockStreamer` 的原位重绘 + 块级定稿、结构性块闭合才成盒、视图外 cap 保留，只在事件名挂接上换。为何选此：这是已记录的有意偏离（`docs/adr/0002`，无 TUI → 需重绘 markdown 块），不在本次范围。为何不改成 pi 的滚动区追加：违背「只对齐抽象、保留有意偏离」。
- **纯函数缝保持**：`EventRenderer`/`MarkdownRenderer` 纯函数缝不变；只把 `MessageEnd` 照旧走渲染（它在生命周期里语义与旧 `MessageEnd` 一致），`MessageStart` 对渲染可为空或重置。

## Testing Decisions

- **单缝优先（主缝）**：`AgentLoop` 的事件发射序列是生命周期的最高缝——用 fake LlmClient 流式回放，断言事件序列为 `MessageStart → MessageUpdate×N → MessageEnd`（含 `aborted` 变体），先例 `AgentLoopStreamingTest`。这是「最少的缝」：事件生命周期全部在 AgentLoop 一处体现。
- **渲染订阅缝（既有，次之）**：`BlockStreamer`/`Main` 按 `MessageUpdate/MessageEnd` 挂接的**外显屏幕状态**用既有忠实终端模拟器（`BlockStreamingResidueTest.TermSim`）断言：流式中途正文可见、恰一份、无裸 markdown、无残留、中断标记、结构性块闭合才成盒、视图外 cap 退化。先例 `BlockStreamingResidueTest` / `MainStreamingTest` / `BlockStreamerTest`。
- **好测试只测外部行为**：断言事件序列名与顺序（`Start/Update…/End`）与终端屏幕状态，不断言内部实现（缓冲、行数账）。
- **零回归**：既有 `BlockStreamingResidueTest`、`BlockStreamerTest`、`MainStreamingTest` 在改名后全部保持绿；改的是事件名与挂接，不改变已绿的行为断言。

## Out of Scope

- 两层 delta（pi-ai 的 text/thinking/toolcall 分片 + agent partial 快照）——有意的简化，见 ADR-0002
- thinking / reasoning 增量事件
- 会话持久化与压缩（另立规格）
- 多 provider + OAuth / transformContext 管线 / AgentHarness hooks / TUI / protocol —— 前瞻架构，非本阶段（ADR-0002）
- 工具调用增量流式展示（保持单行摘要）
- 渲染机制本身的重构（原位重绘 + 块级定稿为有意偏离，保留）

## Further Notes

- **对齐参照**：pi 的 `message_start / message_update / message_end` 生命周期；mini-code 只取「三事件 + 单层 delta」对齐抽象，不复制 pi-ai 的两层分片（显式省略，理由见上）。
- **取代关系**：本规格把 `.scratch/incremental-stream-render/spec.md` 里的 `StreamDelta` 事件更名/升级为 `MessageUpdate`，并补齐 `MessageStart`；`MessageEnd` 概念不变。
- **术语**：`CONTEXT.md` 的 `消息生命周期` 与 `流式增量`（关联 `MessageUpdate`）、`有意偏离` 已同步本规格（本轮 domain-modeling 已写）。
