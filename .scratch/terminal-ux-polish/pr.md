# PR: 终端体验打磨（行式 REPL）

状态: **draft**

Closes: `.scratch/terminal-ux-polish/spec.md`
Closes tickets: `issues/01` · `issues/02` · `issues/03` · `issues/04`

## 概要

按规格实现行式 REPL 体验打磨：事件渲染抽离为纯函数并落地 NO_COLOR/非 tty/TERM=dumb 降级；工具行摘要与四色角色配色；启动横幅一行 + 轮末耗时统计；输入编辑器增强（❯ 提示符、历史持久化、多行输入）。零新增第三方依赖。

## Tickets

- [ ] 01 事件渲染抽离与降级接线
- [ ] 02 工具行摘要与四色
- [ ] 03 横幅一行与轮末耗时
- [ ] 04 输入编辑器增强

## 验收

`mvn test` 全绿 + 渲染器纯函数单测 + pty 实测冒烟（详见各票）。
