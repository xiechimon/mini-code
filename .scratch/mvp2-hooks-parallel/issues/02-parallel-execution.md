# 02: 工具并行执行（ToolKind 分组 + daemon 池 + fail-fast）

**What to build:** 读 `.scratch/mvp2-hooks-parallel/spec.md`（Implementation Decisions 后 4 条 + Testing Decisions）与 `docs/adr/0005`。在 ticket 01 的钩子缝之上，把同 turn 多 tool_calls 的执行改为「READ_ONLY 连续段并行、STATEFUL 串行」。每步遵循 ADR-0005 偏离记录。

**Blocked by:** 01

**Status:** blocked

## 交付物

1. `dev.minicode.tools.ToolDefinition` 扩展：
   - 嵌套 `enum ToolKind { READ_ONLY, STATEFUL }`（或同包顶层，实现者定，javadoc 注明 fail-safe 理由）
   - `default ToolKind kind() { return ToolKind.STATEFUL; }`——**默认串行**
   - `ReadTool` override 为 `READ_ONLY`；Write/Edit/Bash 不动（吃默认）。
2. `AgentLoop` 并行执行器：
   - 字段：懒创建的 `ExecutorService`（`Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())`，线程 daemon + 命名 `mini-code-tool-N`）；**首次遇到 `toolCalls.size() > 1` 才创建**；不实现 AutoCloseable（daemon 随 JVM 退，模式对齐 `OpenAiCompatClient.doStream` 的 monitor 线程）。
   - 分组语义：按 LLM 发出顺序把 toolCalls 切成**连续段（runs）**——同段内全 READ_ONLY 则并行提交，含任一 STATEFUL 则整段按序串行。段与段之间保持先后。示例：`[read1, read2, write1, read3]` → parallel(read1,read2) → serial(write1) → serial(read3)。
   - 钩子在**每个工具自己的执行路径内**照 ticket 01 走（并行组内 per-tool 独立票决，钩子实现需自知可能被并发调用——ToolHook javadoc 注明）。
   - 事件：`ToolStart` 在各自任务开始时发（并行下可交错，可接受）；`ToolResultEvent` 在完成时发（实时反馈）；**回 LLM 的 toolResults 列表严格按 LLM 发出顺序**组装。
   - fail-fast：并行组内任一任务异常（钩子失败除外——那是 isError 结果不是异常）→ `Future.cancel(true)` 组内未完成者；已完成的结果保留；被取消的构造 `ToolResult.error("cancelled: 同组工具失败")` isError 结果；整轮不中断（LLM 下一轮看到错误集）。
   - 取消/中止：工具执行阶段每段开始前检查 `isCancelled`（沿用现有 trigger 语义——注意 trigger 现仅在 streamOnce 内取用，本 ticket 把「回合级取消检查」加在工具段间即可，**不**改 trigger 生命周期）；已提交的组遇中止同样 `cancel(true)`。
   - `stopReason=length` 分支不变（不走并行）。
3. `BashTool` 中断安全：`execute` 的 `proc.waitFor` 抛 `InterruptedException` 时 → `proc.destroyForcibly()` + 恢复中断位 + 返回 `ToolResult.error("命令被中断")`（或抛出，实现者定，但**进程必须 destroy**）。javadoc 注明：execute 无 signal（偏离 pi），取消靠线程中断。

## 验收

- [ ] `kind()` 默认 STATEFUL；ReadTool 为 READ_ONLY（单测断言）
- [ ] 同 turn 双 READ_ONLY：CountDownLatch 互等测试证明真并发（超时 5s，只有并行才过）
- [ ] 双 STATEFUL：进入/退出顺序记录断言串行（不写 timing 断言）
- [ ] 混合 run `[read, write, read]`：write 在两个 read 之间执行（顺序记录断言）、结果按 LLM 顺序回收
- [ ] fail-fast：组内一个工具抛异常 → 兄弟被取消 → isError「cancelled」结果、LLM 收到全组结果、循环继续
- [ ] BashTool 中断：执行 `sleep 30` 的 bash 工具在线程 interrupt 后 2s 内返回 error 且进程被 destroy（`ps` 或 exitValue 断言）
- [ ] ticket 01 的钩子测试在并行路径下仍绿（per-tool 独立票决：并行组内一个 BLOCK 不影响兄弟）
- [ ] 单工具回合与全部既有测试（229+ticket01 新增）零回归
- [ ] `mvn test` 全绿

## Comments

（实现过程中的决策追加于此）
