---
status: accepted
---

# Context 压缩对齐 pi 的 compaction（增量偏离点）

## Background

REPL 多轮对话已可持久化（`docs/adr/0003`），但只解决了「进程退出不丢」；还没解决「**对话过长不爆 token**」（MVP3 的另一半）。当前 REPL 用 `if (history.size() > 50) { subList(0, toRemove).clear(); }`（`Main.runReplTurn`），超出后**直接丢弃**最旧消息，不留上下文线索，是粗暴兜底。

## Decision

采用对齐 pi 的 **compaction**：

- **触发混合**：阈值自动（`contextTokens > contextWindow − reserveTokens`，agent_end 后判定）+ 手动命令 `/compact`（REPL 输入命令即压）；可在本轮不重试当前轮。
- **写入**：`compaction` JSONL 条目，字段 `summary / firstKeptEntryId / tokensBefore`，完整历史仍存文件（append-only），压缩只是给上下文视图换头。
- **重建**：取叶子路径上**最后一个** `compaction` 打头，拼接 `[firstKeptEntryId..压缩点]` 保留段 + 压缩点后新增，投影为 `<summary>` 系统消息。
- **摘要生成**：单次 LLM 调用，**简化 4 段**结构化检查点（`Goal / Progress / Key Decisions / Next`，详见偏离），落 `Summary` 系统消息；已有摘要做增量合并；拒绝 `stopReason=length` 的半截。
- **`contextTokens` 来源**：优先 provider 响应 `usage`（新增 `parseResponse`/`SseParser` 采集），回退 `chars/4` 启发式（图片 4800 字符/张，对齐 pi）。
- **防误伤护栏**：usage 时间戳早于最近压缩条目跳过；跨模型 usage 跳过（小窗口切大窗口后旧模型溢出错误不应触发新模型压缩）。
- **范围**：仅 REPL。
- **失败降级**：摘要 LLM 失败/无 API key → 降级为「裁剪不摘要」不中断 REPL。

## Considered Options

- **pi 全套（选定）**：APPEND-ONLY + 触发阈值 + 4 段摘要 + 内存/文件分离，是未来 branch/resume 自动可叠加的最小集。
- **仅 cap-50 直接丢**：现有实现，无摘要、信息不可恢复，违背「长对话不爆 token」验收。
- **滑窗式裁剪（无摘要）**：按 token 切窗口丢掉旧消息，不解决「老人还需要被 LLM 看到」——pi 与 mini-code 都不取。
- **全真 SQLite/DB**：引入依赖，且偏离 alignment；pi 不取，本项目同样不取。

## Consequences

**有意偏离**（按 `CONTEXT.md` 术语「有意偏离」逐条记录）：

- **摘要格式：4 段 vs pi 6 段**。理由：pi 6 段检查点是为其强会话/复现设计的；mini-code「先跑通」，4 段够用且落 `<summary>` 消息不过冗。**未来加段只需在 Compactor 提词模板里加**，写文件时无需改。
- **预算参数走 `CompactionConfig` 常量 + env** 而非 `settings.json`。理由：仓库无 `settings.json` infra（pi 是 monorepo 级 setup system，mini-code 单模块 Maven 无对应），常量默认 + env 覆盖最务实。**未来若用户要求，加 `~/.mini-code/settings.json` 加载即可，不破坏调用缝**。
- **`Model` record 不掺 `contextWindow` 字段**。理由：Model 是「我请求哪个模型」的纯数据，掺预算会与触发逻辑耦合，引发「同一模型不同窗口」（思考预算 vs 历史预算）歧义；用 `CompactionConfig` 单独管。**未来若 model-aware 差异化窗口，按 `provider.model` 在 `CompactionConfig.resolve(model)` 加 map**。
- **未做：(a) `/compact` 命令解析** (b) **overflow 自动触发分支**（API 报溢出/截断时压缩并重试一次）。理由：(a) REPL 命令解析属 MVP4 的 TUI 范围，本轮仅阈值自动 + 后接入口；(b) overflow 触发要改 `OpenAiCompatClient`/`AgentLoop` 错误通路，量大于阈值自动，先跑通易入口。

**首落（在 `dev.minicode.session` 包内新增）**：
- `CompactionConfig`：常量（`reserveTokens=16384`、`keepRecentTokens=20000`，默认窗口 128k）+ 可选 env 覆盖（`MINICODE_CONTEXT_WINDOW` 等）。
- `ContextCompactor`：单类封装触发判定 + LLM 摘要调用 + 写 `compaction` 条目；提供 `shouldCompact(history)` 与 `compact(history, llmClient)` 两个公共方法。
- **不**做：第二包；`SessionManager`/`AgentLoop` 不掺压缩逻辑（独立类负责）；`SessionEntry` 类型常量扩到 3 种（`session` / `message` / `compaction`）；`SessionStore` 无需改动（已支持任意 `SessionEntry` JSON）。
- **顺带修**：现有 REPL `cap-50` 改为「按压缩预算驱动」，不再盲丢（失败的 fallback 再沿用 cap-50 兜底）。

**后续加项**：branch/resume 读重建、overflow 自动分支、`/compact` 命令、provider-specific 窗口模型、跨模型的 `compaction` 含义继承、usage 采集与防误伤、REPL 触发接线补完、`Message` 加 id 字段、`firstKeptEntryId` 取值、阈值单位统一为 token。

## 本节范围局限（落地后回填）

2026-09-10 spec/standards 双轴 review 落地后核对（详见 commit message），本节交付物的**实际范围**如下：

- **已就位**：`CompactionConfig`、`ContextCompactor`、`SessionContext` 三个新类；`SessionEntry` 增 `TYPE_COMPACTION` 与三个新字段；`SessionManager` 增 `appendCompaction` 方法；`SessionHistory.replaceKeepingInMemory` 辅助方法（用于未来压缩接线，不动文件）；CONTEXT.md 增四个术语；现存 cap-50 顺手去除。
- **未接线**：REPL 主循环三路径（管道/JLine/Scanner）**不**自动压缩。`firstKeptEntryId` 写死 null，保留段投影路径成死码（重建视图仅含 `<summary>` + 压缩点后新增）。threshold 单位混用（chars vs tokens 在同一比较）。`usage` 采集未做，故防误伤护栏亦未做。
- **本节所有上述限制已在 ADR 「未做」清单中列出，并附下一节交付建议**。
