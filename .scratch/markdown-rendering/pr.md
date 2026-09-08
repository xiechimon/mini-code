# PR: Markdown 正文渲染

状态: **ready**

Closes: `.scratch/markdown-rendering/spec.md`
Closes tickets: `issues/01` · `issues/02` · `issues/03`

## 概要

助手回复正文经 Markdown→终端文本渲染管线呈现：commonmark + ext-gfm-tables 解析（ADR-0001，项目首个运行时依赖）、自研主题渲染器（标题粗青/行内代码绿/代码块边框盒/列表青点/引用灰斜/链接下划线暗 URL/表格自适应对齐）、ANSI 感知折行、PLAIN 结构保留降级。工具结果与轮首保持既有摘要渲染。

## Tickets

- [x] 01 正文渲染管线贯通
- [x] 02 块级结构：代码块盒/列表/引用
- [x] 03 表格自适应

## 验收

`mvn test` 全绿 + 渲染器纯函数两态断言 + pty 实测（含规格中的 Maven 表格痛点样例场景）。

## 验收记录

- code-review 双轴通过（Standards 零硬违规；Spec 全项对齐，删除线改官方扩展）
- 修复：ext-gfm-strikethrough / 死代码清除 / AnsiTextUtil 收敛 / 宽度归一单点 / 窄宽串色测试
- mvn test：139 passed
