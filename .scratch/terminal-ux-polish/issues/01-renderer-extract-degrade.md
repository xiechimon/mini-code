# 01: 事件渲染抽离与降级接线

**What to build:** 把当前散在入口里的事件渲染（轮首提示、助手文本、工具调用行、工具结果行、完成行）收敛为独立的事件渲染器纯函数——输入 agent 事件与样式开关，输出文本；交互模式与管道模式统一经它出字。同时落地样式探测：`NO_COLOR` 非空、非 tty、`TERM=dumb` 任一命中即去色。本票行为保持：文案与现状等价，只挪位置和立降级。这是「先让改动变容易」的 prefactor，为 02/03 的渲染规则铺路。

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

- [x] 交互与管道两模式事件输出经同一渲染器，文案与现状等价（REPL 冒烟 + 现有测试不回归）
- [x] 样式探测单测：NO_COLOR 非空 / 非 tty / TERM=dumb 三分支去色，默认 tty 有色
- [x] 渲染器为纯函数：不读环境、不碰时钟（耗时由调用方注入）
- [x] 渲染器输出字符串断言的单测落地（先例 MainReplTest 缝）
- [x] mvn test 全绿，零新依赖
