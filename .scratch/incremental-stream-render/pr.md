# PR: 行级流式渲染（开放块原位重绘 + 块级定稿）

状态: **ready**

Closes: `.scratch/incremental-stream-render/spec.md`
Closes tickets: `issues/01` · `issues/02` · `issues/03` · `issues/04`

## 概要

流式期间正文**可见地增长**，取代「块级流式计数器」（只显示一根 `▌ 已生成 N 字` 进度行、块完成才整块倾销）的治标方案。行级流式 + 块级定稿：普通段落/标题随增量**原位重绘**（CUU+ED 清旧重打，正文实时增长）；代码围栏/缩进代码/GFM 表格等**结构性块闭合才成盒/成表**（不逐字重绘）；已定稿块从此不可变。沿用 `MarkdownRenderer` 纯函数缝。

## Tickets

- [x] 01 段落流式可见增长 + 块定稿（tracer bullet）
- [x] 02 结构性块闭合才定稿（围栏/缩进代码/表格）
- [x] 03 开放块视图外 cap（超视口退化追加）
- [x] 04 Main 接通视口高度 + 冒烟 + 清理

## 验收

`mvn test` 全绿（217 passed）+ 既有 `BlockStreamingResidueTest` 无残留/无重复断言保持绿 + 新增残渣测试（表格/缩进代码开放期不重复）+ 回归测试（流式中途正文可见、不退化计数器）+ 终端模拟器视图外 cap 退化验证。

## 验收记录

- code-review 双轴通过（Standards 头档 pi 对齐路径+擦除收敛 + Spec 表格提前识别均已修）
- 修复：BlockStreamer 类头补 pi 对齐路径 / 擦除序列收敛为 eraseOpenBlockRegion/erasePlaceholder / 删死代码 completeBlockIfNeeded / redrawToStart 更名 / Main 双 EventSink 收敛为 streamingSink / isTableShape 提前识别表头并清切换残留 / 探针回归并入 BlockStreamerTest（除重）
- mvn test：217 passed（含既有 BlockStreamingResidueTest 无残留/无重复断言 + 新增表格/缩进代码残渣测试 + 视图外 cap）
- pty 冒烟：真实流式段落可见增长、Ctrl-C 中断保留 partial + ⏹ 标记、长代码块闭合整块成盒、结构块闭合才定稿

## 关键设计（取代块级计数器）

- **开放块原位重绘**：增量打块缓冲，每增量重算本块渲染形态，CUU 回本块首行 + ED 清到底 + 重打印。正确性来源：已定稿块从不重绘，滚出视口即终态，绝不与重绘叠加双份。
- **块级定稿**：已完成块前缀（空行分界/围栏闭合）立即一次打印定稿、不可变；剩余开放块继续重绘。避免多块同处一开放块导致顶部清不掉的残留。
- **结构性块 seal-only**：围栏/缩进代码/表格开放期不逐字重绘（避免大面积闪烁与滚动擦除），闭合才成盒/成表（配合项目行式终端约束）。
- **视图外 cap**：开放块渲染行数超出注入的视口高度时，放弃 CUU+ED 原位重绘、转追加模式——增量原样上屏、不丢内容不重复，结构性块靠闭合定稿天然规避长容器滚动问题。
- **首增量覆盖「思考中」占位行**；管道（非 tty）模式保持非流式、零光标控制序列；`MarkdownRenderer`/`Style` 纯函数缝不变。
