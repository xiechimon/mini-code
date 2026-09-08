# 01: Message 生命周期对齐（Start/Update/End 替换 StreamDelta/MessageEnd）

**What to build:** 读 `.scratch/message-lifecycle/spec.md`。把事件层对齐成 pi 的消息生命周期：`AgentEvent` 密封族新增 `MessageStart`，把 `StreamDelta` 更名 `MessageUpdate`（语义不变、单层 delta），`MessageEnd` 保留（携带最终完整 Message + stopReason，`aborted` 作为 stopReason 变体）。`AgentLoop` 按 `MessageStart → MessageUpdate… → MessageEnd` 发射；渲染订阅层（`Main` 流式 sink + `BlockStreamer`）按生命周期挂接——`MessageUpdate` 喂增量、`MessageEnd` 触发定稿/中断标记。旧的 `StreamDelta` 命名随重构移除（不保留双轨）。渲染机制本身（原位重绘 + 块级定稿）不变。每步遵循 ADR-0002「只对齐抽象、有意偏离已记录 + 为何选此/为何不做」。

**Blocked by:** None（可立即开始）。

**Status:** ready-for-agent

- [ ] `AgentEvent` 密封族：新增 `MessageStart`、`StreamDelta`→`MessageUpdate`、`MessageEnd` 保留；`MessageStart/Update/End` 事件可被穷举
- [ ] `AgentLoop` 对每个助手消息发射 `MessageStart → MessageUpdate×N → MessageEnd`（含中断时 `MessageEnd.stopReason=aborted`）
- [ ] 旧 `StreamDelta` 命名全库移除，无双轨
- [ ] `Main` 流式 sink + `BlockStreamer` 按生命周期挂接：`MessageUpdate`→`delta`、`MessageEnd`→`flush(aborted)`、`MessageStart` 标记消息开始（刷新流式状态）
- [ ] 渲染机制不变：流式中途正文**可见增长**、恰一份、无裸 markdown、无残留、结构性块闭合才成盒、视图外 cap 退化——全部保持绿
- [ ] 中断（Ctrl-C）保留 partial + 「⏹ 已中断」标记、进程不退出——语义不回归
- [ ] `MessageStart/MessageUpdate/MessageEnd` 纯函数缝（EventRenderer/MarkdownRenderer）不被破坏
- [ ] 测试：`AgentLoopStreamingTest` 断言 Start/Update…/End 序列（fake LlmClient 流式）；`MainStreamingTest`/`BlockStreamingResidueTest`（TermSim）在改名后零回归
- [ ] 全量 `mvn test` 绿；`CONTEXT.md` 词汇（消息生命周期/流式增量→MessageUpdate）协调一致
