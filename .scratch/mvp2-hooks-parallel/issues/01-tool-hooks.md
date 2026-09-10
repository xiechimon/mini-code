# 01: before/afterToolCall 工具钩子（三档 ToolDecision）

**What to build:** 读 `.scratch/mvp2-hooks-parallel/spec.md`（Implementation Decisions 前 4 条 + Testing Decisions）与 `docs/adr/0005`。在 `dev.minicode.agent` 包新增钩子缝并接入 AgentLoop 工具循环。每步遵循 ADR-0005 的偏离记录与「为何选此/为何不做」。

**Blocked by:** None（可立即开始）。

**Status:** ready-for-agent

## 交付物

1. `dev.minicode.agent.ToolCallEvent`——record `(Message.ToolCall toolCall, Map<String,Object> arguments)`：beforeToolCall 的载荷（对齐 pi `tool_call` 事件的 input 可变引用语义，用「替换整个 event」实现改写）。
2. `dev.minicode.agent.ToolDecision`——record `(Action action, String reason, Message.ToolCall modifiedCall)`；`enum Action { PROCEED, BLOCK, MODIFY }`；静态工厂 `proceed()` / `block(String reason)` / `modify(Message.ToolCall newCall)`。
3. `dev.minicode.agent.ToolHook`——interface，两个 default 方法：
   - `default ToolDecision beforeToolCall(ToolCallEvent event) { return ToolDecision.proceed(); }`
   - `default void afterToolCall(AgentEvent.ToolResultEvent event) { }`
4. `AgentLoop` 改造：
   - 新字段 `private final List<ToolHook> hooks`；新构造器重载接 `List<ToolHook>`（null → 空列表）；**现有全部构造器委派，默认空 hooks，行为零变化**。
   - 工具循环（现 `AgentLoop.java:143-169` 的 for）内、`def.execute` 前走钩子链：按 list 顺序调 `beforeToolCall`；`BLOCK` → 短路（后续钩子不再调），不执行工具，构造 `ToolResult.error("blocked by hook: " + reason)`；`MODIFY` → 用 `modifiedCall` 替换当前 ToolCall 与 arguments，**继续**走后续钩子（后续钩子看到修改后的）；钩子自身抛异常 → 短路，构造 `ToolResult.error("hook failed: " + e.getMessage())`，工具不执行。
   - BLOCK/hook-fail 路径与正常路径一样：发 `ToolResultEvent`、结果消息追加进 contextMessages/newMessages（LLM 下一轮能看到 isError 结果）。
   - `execute` 完成（或 BLOCK）后：按序调每个钩子 `afterToolCall(resultEvent)`，单个钩子异常仅 `log.warn`，不影响结果与其他钩子。
   - `stopReason=length` 分支（L128-140）**不走**钩子（那是构造错误结果，不是真执行）。
5. `ToolStart` 事件发射时机不变（钩子链之后、execute 之前照常发；BLOCK 时**不发** ToolStart——工具没有开始）。

## 验收

- [ ] 无钩子注册时：全部既有测试（229）零改动全绿
- [ ] PROCEED：工具正常执行，before/after 各记录一次（RecordingHook 断言调用序列）
- [ ] BLOCK：工具不执行（文件系统无副作用断言）、`ToolResultEvent.isError=true` 且 output 含 reason、不发 ToolStart、LLM 收到 isError 结果消息、循环继续（下一轮 fake 能看到该结果）
- [ ] MODIFY：工具收到修改后的 arguments（如 read 的 path 被改）、后续钩子的 beforeToolCall 看到 modifiedCall
- [ ] 双钩子短路：hook1 BLOCK → hook2.beforeToolCall 未被调用
- [ ] beforeToolCall 抛异常 → isError「hook failed」结果、工具不执行
- [ ] afterToolCall 抛异常 → 结果不变、不崩、其余钩子照常
- [ ] 新测试全在 `src/test/java/dev/minicode/agent/`（RecordingHook 放测试树，可复用）
- [ ] `mvn test` 全绿

## Comments

（实现过程中的决策追加于此）

- 2026-09-10 实现记录：
  - **afterToolCall 触发面**：按票面字面「execute 完成（或 BLOCK）后」——正常执行与 BLOCK 均按序调 afterToolCall；beforeToolCall 抛异常（hook failed）路径只发 ToolResultEvent + isError 结果回 LLM，不发 ToolStart、也不走 afterToolCall（视为链前中断，票面未要求对该情形观测；事后扩充容易）。
  - **未知工具 def==null 不走钩子**：与 MVP1 完全一致（无 ToolStart/ToolResultEvent 发射，仅回「未知工具」isError 结果消息），钩子缝只挂在「找到工具且将执行」的路径。
  - **beforeToolCall 返回 null 视为 PROCEED**（防御空返回值，与默认放行语义一致）。
  - **MODIFY 语义边界**：替换的 ToolCall 的 id/argumentsJson 由钩子负责保持（工具结果与 tool_call_id 需与 LLM 配对，改 id 会破坏配对）；AgentLoop 只负责把 currentCall（可能已被替换）用于 execute 与结果消息。
  - **环境性失败（与本票无关）**：票面基线 229 测试中 `OpenAiCompatClientStreamingTest.realGatewaySmokeIfKeyPresent` 在本机既有失败——auth.json/.env 解析出的 key 余额不足，真实网关返 401（`Insufficient balance`），属环境态而非代码回归；本票前后该单条失败保持一致，其余 228 + 新增 10 条全绿。
