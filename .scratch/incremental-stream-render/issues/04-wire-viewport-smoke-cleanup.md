# 04: Main 接通视口高度 + 真实网关 tty 冒烟 + 清理契约

**What to build:** 读 `.scratch/incremental-stream-render/spec.md`。把交互路径（JLine REPL）与 one-shot tty 的**终端高度**取出来（`terminal.getHeight()` / 探测终端），注入渲染组件供视图外 cap 使用；用真实网关做 tty 冒烟：流式段落**可见地逐段增长**、生成中 Ctrl-C 优雅取消且**进程不退出**、已生成 partial 保留并追加「⏹ 已中断」、长代码块闭合时整块成盒。收尾：删除抛掷原型，全量 `mvn test` 绿（含既有 `BlockStreamingResidueTest` 无残留/无重复断言 + Markdown 渲染零回归）。

**Blocked by:** 02, 03（需 seal-only 与 cap 齐备后做全链路集成与冒烟）。

**Status:** ready-for-agent

- [ ] 交互与 one-shot tty 路径把终端高度注入渲染组件（供 cap 用）
- [ ] 真实网关 tty 冒烟：段落可见增长、Ctrl-C 中断保留 partial + 追加中断标记、进程不退出、长代码闭合成盒
- [ ] 管道（非 tty）模式行为不变：零 StreamDelta、零光标控制序列
- [ ] 抛掷原型已删除，无 `[DEBUG-*]` 残留
- [ ] 全量 `mvn test` 绿；既有 residue 断言「无裸 markdown/无重复/无进度残留」保持绿
- [ ] 既有 Markdown 渲染（代码盒/表格/标题/粗体/颜色/宽度注入）测试零回归
