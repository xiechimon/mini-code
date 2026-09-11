---
status: accepted
---

# MVP5 起对齐标的从 pi 切换为 Claude Code

pi 的四层参照（pi-ai / pi-agent-core / pi-coding-agent / 斜杠命令层）已在 MVP1~4 落地完毕。pi 奉行「六个 No」（无 MCP、无 plan mode、无内置 TODO、无 extensions 内置实现——下放给 Extensions 生态，见 ADR-0002 与 pi coding-agent README Philosophy 节），而 mini-code 下一阶段目标恰是 Claude Code 式的内置 MCP / Skill / PlanMode：对齐标的与目标正面冲突，必须换标的。

决策：**pi 对齐线到 MVP4 收官；MVP5 起语义标的切换为 Claude Code 的内置三件套**，每项取「常规版」最小切法：

- **MVP5 = PlanMode**：`/plan` 斜杠命令进出；注册 `ExitPlanMode` 工具供模型提交计划，终端打印计划并 y/n 征求批准，批准后自动切回执行模式；plan 模式期间 STATEFUL 工具经 beforeToolCall 钩子全部 BLOCK（reason 对模型可见）、READ_ONLY 放行。复用 ToolKind + 三档钩子，零新抽象。
- **MVP6 = Skill**：扫描 `~/.minicode/skills/` 与项目级 `.minicode/skills/` 下 `*/SKILL.md`；渐进披露——仅 frontmatter 的 name+description 常驻注入 systemPrompt，正文调用时才读入上下文；调用入口双轨 = 模型侧 `Skill` 工具 + 用户侧 `/skill-name` 斜杠命令。
- **MVP7 = MCP**：只做 client、只做 stdio、只做 tools（映射进现有 `Tool` 协议），配置文件格式对齐 `.mcp.json`；resources / prompts / HTTP transport 列为 MVP7+ 迭代。

不做：TUI（放弃对齐 pi-tui）、Extensions 机制（pi 的逃生门，随换标的一并放弃）。

## Considered Options

- **继续对齐 pi**：MVP5 只留 TUI 收官四层，MCP/PlanMode 移出主线。放弃：用户明确要 Claude Code 式内置能力，TUI 无学习价值。
- **对齐 pi 逃生门**：先做 Extensions 机制，MCP/PlanMode 作为前两个扩展。放弃：多一层间接，且与「常规的 MCP/Skill/PlanMode」目标不符。
- **换标的 Claude Code 直接内置（选定）**：语义路径最短，学习价值直接来自 Claude Code 的真实机制。

## Consequences

- 失去「六个 No」这条现成的范围护栏；范围改由每项 MVP 的最小切法与验收标准守。
- ADR-0002 保持历史有效（MVP1~4 的 pi 对齐决策不变），本 ADR 只覆盖 MVP5+。
- 单模块继续不拆（拆分的唯一动因 TUI 已取消）；MCP client 代码放 `dev.minicode.tools` 还是新包，MVP7 开工时再定。
- 验收基线不变：`mvn test` 全绿 + 真实 LLM E2E；三项各补一条端到端验收（见 CONTEXT.md「MVP 切分」）。
