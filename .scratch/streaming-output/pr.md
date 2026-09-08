# PR: 流式输出（等待反馈面落地）

状态: **draft**

Closes: `.scratch/streaming-output/spec.md`
Closes tickets: `issues/01` · `issues/02` · `issues/03` · `issues/04`

## 概要

助手回复逐字流式上屏：commonmark 终态重绘前的流式增量（`MessageDelta` 事件）、SSE 解析器纯函数缝、客户端可取消流式与非流式回退、Ctrl-C 优雅中断（partial+aborted 保留）、管道模式非流式。对齐 pi 的两层流式结构（pi-ai delta 流 / agent 层 partial 更新），适配行式 REPL。

## Tickets

- [ ] 01 真实网关流式 spike + SSE 解析器
- [ ] 02 客户端流式与回退
- [ ] 03 事件协议与中断语义
- [ ] 04 渲染重绘与 REPL 收尾

## 验收

`mvn test` 全绿 + SSE 解析器协议穷举 + fake SSE 集成（含取消时序）+ 真实网关 spike 笔记 + pty 实测（真实流式 + Ctrl-C 中断不退进程 + 中断后继续对话）。
