---
status: accepted
---

# 用 commonmark 做 Markdown 解析

正文渲染需要把助手消息的 Markdown 转为终端文本。我们选 commonmark（加 ext-gfm-tables 官方扩展）做解析、自研主题渲染器做呈现，对齐 pi 的「parser 库 + 自研主题」架构（pi 用 marked + 自研 markdown.ts）。这是项目的第一个运行时新依赖：解析器自研需覆盖 CommonMark 数百条规范用例，不值得；渲染层保持纯函数与手写 ANSI 的既有原则不变。

## Considered Options

- **flexmark**：功能全但重、传递依赖多，与极简内核定位冲突
- **手写解析**：零依赖但规范覆盖成本高，pi 也没自研解析
