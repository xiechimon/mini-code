# 01: 段落流式可见增长 + 块定稿（tracer bullet）

**What to build:** 读 `.scratch/incremental-stream-render/spec.md`。把流式渲染组件从「单行进度计数器」改为「开放块原位重绘 + 已定稿块不可变」：流式期间助手段落文字到达即渲染、即显示、随增量原地更新，用户能看到正文**实时增长**，而不是只看到「▌ 已生成 N 字」计数器；块以空行分界定稿后一次打印、永不再重绘。屏幕任何时刻都是渲染后的正文，无裸 markdown、无双份、无残留。首个流式增量覆盖 TurnStart 的「思考中」占位行。对外 `delta`/`flush(aborted)`/`reset` 接口保持不变（仅重写内部实现）。

**Blocked by:** None（可立即开始）。

**Status:** resolved

- [x] 流式中途屏幕上是**可见的正文**（而非仅"已生成 N 字"计数器）——以忠实终端模拟器断言
- [x] 段落随增量**原地增长**，块以空行分界定稿后最终屏幕**恰一份**、无裸 `#`/`**` 标记、无残留
- [x] 首个流式增量覆盖「思考中」占位行，屏幕上不残留占位信息
- [x] `delta`/`flush`/`reset` 接口不变，`Main` 事件挂接零改动即可编译
- [x] 中断 `flush(aborted)` 仍保留 partial 并追加「⏹ 已中断」标记
- [x] `MarkdownRenderer` 仍为纯函数缝（输入 markdown/样式/宽度→输出），未读环境、未碰时钟
- [x] 既有 `BlockStreamingResidueTest` 的「无裸 markdown/无重复/无进度残留」断言保持绿
- [x] 既有 Markdown 渲染（代码盒/表格/标题/粗体/颜色/宽度注入）测试零回归
