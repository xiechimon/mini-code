---
status: accepted
---

# Session 持久化对齐 pi 的 append-only JSONL 树

## Background

mini-code 的 REPL 多轮对话目前只在内存持 `List<Message> history`（cap 50），进程退出即丢；MVP3 后半是「Session 持久化 + Context 压缩」，本 ADR 定持久化的**形态**（压缩另立规格）。

## Decision

采用对齐 pi 的 **append-only JSONL 树**：

- 每条记录带 `id`/`parentId` 建树，`leaf` 指针定当前对话位置；branch/resume/compact 均为指针/新增操作，**历史记录从不修改**。
- 记录 `id` 用 **8-hex 短 ID**（碰撞重试 100 次→UUID）；会话标识为**文件头独立 uuid**，与网关路由 id（`x-opencode-session`）分开。
- 会话按 **cwd 分桶** 存 `~/.mini-code/sessions/--<cwd 编码>--/`（基目录沿用仓库既有用户级目录 `~/.mini-code`，见 `Main.defaultHistoryPath`；无 `agent` 子段）。
- 条目类型取**最小集**：`session` 头 + `message`；`compaction` 预留，type 命名空间对齐 pi（`session`/`message`/`compaction`/…），能力落地即加。
- `message` 条目为「已完成消息」（含消息文本、stopReason、工具调用与结果）；`MessageUpdate` 为瞬时展示增量、**不落盘**，只落最终态。
- `message` payload 用 mini-code 现有 `Message` 形状（`Content.toolCall/toolResult` 承载工具链路），entry 外壳负责树身份（`id`/`parentId`/`timestamp`），**不重映射**成 pi 的多角色条目。

为何选此：本项目以 pi 为对齐标的（`docs/adr/0002`），日志式 JSONL + 树指针是理解 pi 会话机制（`docs/wiki/21`）的关键；branch/resume/compact 作为树的指针/新增操作天然可叠加。

## Considered Options

- **对齐 pi 的 JSONL 树（选定）**：为 branch/resume/compact 预留缝，与 pi 的 SessionManager 同构。代价：需维护树/leaf 指针，略增复杂度。
- **简单线性日志**（每行一条消息、无树/parentId）：更易实现，但无法表达 fork/恢复/摘要压缩，违背「对齐 pi 抽象」的既定方向（`docs/adr/0002`）。
- **存 SQLite / DB**：引入依赖，且 pi 用文件日志，偏离 alignment。

## Consequences

- 首落：新增包 `dev.minicode.session`——`SessionManager`（对齐 pi 名，管树/leaf/追加）+ `SessionStore`（文件 IO，每消息 append）+ `SessionEntry`（schema）+ `SessionHistory`（REPL 历史装饰器，addAll/add 时落盘）。
- 本轮只做**写入侧**：新建会话 + 每条已完消息 append（id/parentId 建链、推进 leaf）。读-重建路径（`open`/`loadContext` 把树重建为 `List<Message>`）留给 resume 迭代，写入侧结构第一天就搭好。
- 单进程 REPL append、**无锁**（pi 的 proper-lockfile 只为 `auth.json` 跨进程写；会话是单写者）。
- 加载健壮性镜像 pi：畸形行跳过、首行须 `session` 头、`leaf` 取物理行序最后记录。
- 有意简化：砍掉 pi 的多角色条目（`custom`/`custom_message`/`label`/`thinking_level_change`/`model_change`/`bashExecution`），因 mini-code 暂无这些能力；type 命名空间对齐 pi，落地即加，不预铺空壳（`docs/adr/0002`）。
