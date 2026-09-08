---
status: accepted
---

# 对齐 pi 架构：只对齐抽象、保留有意偏离

mini-code 以 `earendil-works/pi`（TS monorepo）为对齐标的。本轮通过 `grill-with-docs` 澄清「对齐」的确切含义：**对齐的是抽象、边界与语义（名称/形状），而非机械复制类型或实现**；对与 pi 不同的点作**有意偏离**并显式记录。

决策：只对齐「已落地 / 正在进行」的功能对应的 pi 抽象，不提前为远期大架构（多模块拆分、transformContext 管线、多 provider、AgentHarness hooks、TUI、CBOR protocol/server）铺空壳。已写成并贴 pi 形状的代码保持其命名/边界/语义一致；已刻意简化的，记录理由。

## Considered Options

- **全量 1:1 复刻**：逐层镜像 pi 包结构与全部能力。违背 mini-code「先跑通 MVP 再优化」的初衷，且 pi 许多能力（多 provider、hooks/Lane、TUI、server/client）是 TS monorepo 级的前瞻架构，非当前阶段所需。
- **MVP 先行、对齐按需**：只对齐「下一个要做」的抽象。会让 mini-code 渐长成自己的样子、与 pi 越差越远，学习的迁移价值下降。
- **只对齐已落地/进行中的功能（选定）**：已写的代码要贴 pi 的形状，未写的按 pi 形状设计；同时接受并记录若干有意偏离。

## 有意偏离（已记录，不再逐行对齐）

- **流式渲染**：mini-code 用「单开放块 + 原位重绘（CUU+ED）+ 块级定稿」；pi 是滚动区逐行追加、从不回擦。理由：mini-code 无 TUI 的 diff-render，坚持 markdown 渲染（代码盒/表格/标题）就必须整块重算；线式终端下「滚出即不可擦」是硬约束。见 `.scratch/incremental-stream-render/spec.md`。
- **增量事件简化**：mini-code 以单一 `StreamDelta`（增量子集）+ 完整 `MessageEnd` 近似；pi 是 `message_start / message_update / message_end` 生命周期 + 两层 delta（pi-ai `*_delta` + agent `message_update` partial）。后续拟对齐事件生命周期。
- **数据模型简化**：`Message` 用单一类 + Role 枚举（pi 为 union）；`Model` 仅 `openai-completions` 一种 api（pi 为 10 种 KnownApi）；工具只 read/write/edit/bash 四件（未用 pi 的 `createAllToolDefinitions` 全集）。

## Consequences

- mini-code 会保留若干与 pi 不同的简化与偏离；每处都需在 ADR / 类头注释里说明「对齐 pi 的 X，但有意简化为 Y」，避免后续误判为忘记实现。
- 对齐是**渐进**的：每次新增/重构功能时贴 pi 形状，而非一次性大改。
- 因 pi 自身奉行「六个 No」（无 MCP、无 sub-agents、无 permission popups、无 plan mode、无内置 TODO、无后台 bash），mini-code 对齐这些点恰好是「共同省略」，无需补装。
