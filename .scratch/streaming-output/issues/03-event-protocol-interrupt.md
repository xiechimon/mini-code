# 03: 事件协议与中断语义

**What to build:** AgentEvent 协议新增流式增量事件（携带 delta 文本片段）——本票显式落实规格对「协议层不动」旧约束的推翻；AgentLoop 改走流式接口：每个片段触发事件发射、累积为完整文本后走既有 MessageEnd 路径，工具调用分发从拼装结果取完整 JSON；引入可注入的中断触发器抽象（置位即取消）：触发后取消 HTTP 请求，已收文本以 partial 助手消息（stopReason=aborted）进入对话历史，本轮尚未执行的工具调用不执行，循环正常收尾。fake 流式客户端覆盖事件序列与 abort 语义，OS 信号不可测性被隔离在触发器抽象之外。

**Blocked by:** 02（客户端流式与回退）

**Status:** ready-for-agent

- [x] 流式增量事件入协议；既有事件族零回归
- [x] AgentLoop 流式路径：增量×N → MessageEnd(final)，事件顺序可断言
- [x] 中断触发器注入：触发后 partial+aborted 进入 history、未执行工具不执行、循环正常收尾
- [x] 触发器未触发时行为与现状一致（既有 AgentLoopTest 零回归）
- [x] mvn test 全绿
