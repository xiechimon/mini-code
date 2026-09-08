# 02: 客户端流式与回退

**What to build:** LlmClient 接口以 default 方法扩展流式能力（默认委派既有同步方法且回调零次——既有 fake 测试零改动）；OpenAiCompatClient 覆写：请求体启用流式标记，异步 HTTP + 输入流响应体逐行喂解析器，文本片段经回调上抛，工具调用 delta 分片累积拼装为完整 Message 返回；SSE 建连失败或解析异常时自动回退一次同步非流式调用并记日志。集成测试用 JDK 内置 HttpServer 起本地 fake SSE 服务（分块+可控制延迟）端到端验证字节→事件→partial，并验证慢速发送时外部取消能及时返回已收部分。真实网关冒烟（有 key 时）。

**Blocked by:** 01（真实网关流式 spike + SSE 解析器）

**Status:** resolved

- [x] default stream() 委派同步方法，既有 fake 测试零改动
- [x] 流式实现：片段逐次回调、返回完整 Message（文本累积 + 工具调用 id/名称/参数分片拼装）
- [x] 回退：SSE 建连失败/解析异常 → 一次非流式重试 + 日志可查
- [x] 取消：外部取消信号及时中断读取，已收部分可用
- [x] 集成测试：fake SSE 端到端（分块+延迟）+ 慢速发送取消时序
- [x] 真实网关冒烟通过（有 key 时），行为与 spike 笔记一致
- [x] mvn test 全绿
