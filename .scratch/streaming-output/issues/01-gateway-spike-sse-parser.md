# 01: 真实网关流式 spike + SSE 解析器

**What to build:** 为流式输出打地基：用真实 key 对 opencode 网关发起 stream 请求，采样各模型（kimi-k2.6/mimo 等）的 SSE 响应原样形态——数据行结构、[DONE] 终止、工具调用 delta 的分片方式、用量块——记入 `.scratch/streaming-output/` 笔记；据此实现 SSE 行解析器纯函数（行序列 → 解析事件：文本增量/完成/工具调用增量/忽略行）并穷举协议测试。本票不改任何产品行为，是后续三票的地基：spike 的真实样例决定解析器的容错面。

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

- [x] spike 笔记落盘 `.scratch/streaming-output/`：各模型真实响应样例、工具调用 delta 形态、与解析器假设的差异点
- [x] SSE 解析器纯函数：行序列 → 事件列表，不碰网络不碰 IO
- [x] 测试覆盖：文本增量分片、[DONE] 终止、注释行/空行忽略、非 JSON 行容错、工具调用增量解析（标记为不外发）、跨块 data 拼接
- [x] mvn test 全绿，现有功能零回归（本票无行为变更）
