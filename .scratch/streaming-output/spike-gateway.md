# Spike: 真实网关 SSE 采样笔记

> 采样时间：2026-09-08  
> 网关：`https://opencode.ai/zen/go/v1`（`provider=opencode-go`）  
> 模型：`mimo-v2.5` / `kimi-k2.6`  
> 端点：`POST /chat/completions`（`stream: true`）  
> 认证：`Authorization: Bearer <REDACTED>` / `x-opencode-session: <REDACTED>` / `x-opencode-client: mini-code-spike`  
> 采样方式：临时 Python 探针（`http.client.HTTPSConnection`，`Content-Type: application/json`），读取 `.env` 的 `OPENCODE_API_KEY` 与 `LLM_BASE_URL`，探针已删除未入库。

**安全声明**：本文档不包含任何真实 API Key、token 或敏感头原文，敏感处一律以 `<REDACTED>` 标注。

---

## 1 采样用例

| # | 模型 | 目标 | 请求 payload 关键 |
|---|------|------|-------------------|
| 1 | `mimo-v2.5` | 文本 | `{"model":"mimo-v2.5","messages":[{"role":"user","content":"Say hello in Chinese, one sentence, no tool calls, answer directly with '你好，世界！'"}],"stream":true}` |
| 2 | `kimi-k2.6` | 文本 | 同上，model 换为 `kimi-k2.6` |
| 3 | `mimo-v2.5` | 工具调用 | 上述 messages 改为 `"Please read file at path 'a.txt' using the read tool. You must call the tool."`，叠加 `"tools":[{"type":"function","function":{"name":"read","description":"read file","parameters":{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}}}],"tool_choice":"auto"` |
| 4 | `kimi-k2.6` | 工具调用 | 同 3，model 为 `kimi-k2.6` |

文本用例刻意要求直接回答 `你好，世界！`，以观察正文分片；工具用例构造必触发 `read` 工具，观察 `tool_calls` 分片。

---

## 2 响应头（两模型一致）

```
HTTP/1.1 200 OK
Date: <REDACTED>
Content-Type: text/event-stream
Transfer-Encoding: chunked
Connection: keep-alive
Cache-Control: no-cache
Server: cloudflare
CF-RAY: <REDACTED>
```

- `Content-Type` 固定 `text/event-stream`，`Transfer-Encoding: chunked`，符合 SSE。
- 无 `Content-Length`，流式分块下发。

---

## 3 SSE 行结构（原样）

### 3.1 心跳 / 注释行

```
: keep-alive

```

- 以冒号开头的注释行，`": keep-alive"`，后跟空行。
- 出现时机：流开始前 1–2 次；长思考期间可能穿插；可安全忽略。
- 规范要求：以 `:` 开头的行视为注释，解析器必须忽略。

### 3.2 数据行

```
data: {"id":"gen-...","object":"chat.completion.chunk","created":1788849433,"model":"mimo-v2.5","choices":[{"index":0,"finish_reason":null,"delta":{"role":"assistant","content":"你好，世界！"}}]}

```

- 每条数据行格式 `data: <json>`（冒号后一个空格，兼容 `data:` 无空格）。
- 每条数据后必跟一个空行（事件边界）。
- `data` 后的 payload 为单个 JSON 对象，或终止标记 `[DONE]`。

### 3.3 终止序列

```
data: {"id":"gen-...","object":"chat.completion.chunk","created":1788849433,"model":"mimo-v2.5","choices":[{"index":0,"finish_reason":"stop","delta":{"role":"assistant","content":""}}],"usage":{"prompt_tokens":268,"completion_tokens":41,"total_tokens":309,"prompt_tokens_details":{"audio_tokens":0,"cached_tokens":192},"completion_tokens_details":{"audio_tokens":0,"reasoning_tokens":0}}}

data: [DONE]

data: {"choices":[],"cost":"0"}

```

- 终止前必有一帧 `finish_reason: "stop"`（文本）或 `"tool_calls"`（工具），`delta.content` 为 `""`，携带 `usage`。
- 紧跟 `data: [DONE]`（精确字符串，前后可有空格）。
- `[DONE]` 后仍有一条 `data: {"choices":[],"cost":"0"}`（opencode 网关额外计费回执），**必须忽略**，视为 `after-done`。

### 3.4 空行

- 事件分隔符，单独一行 `""`。
- 连续空行或首帧前空行均应忽略。

---

## 4 文本增量分片

**mimo-v2.5**：聚合度高，示例 `"你好，世界！"` 一次性返回一个 `content`：

```json
{"choices":[{"delta":{"content":"你好，世界！"}}]}
```

**kimi-k2.6**：细粒度逐字/标点分片，同一句话拆为 4 帧：

```json
{"choices":[{"delta":{"content":"你好"}}]}
{"choices":[{"delta":{"content":"，"}}]}
{"choices":[{"delta":{"content":"世界"}}]}
{"choices":[{"delta":{"content":"！"}}]}
```

**解析器结论**：必须按帧累积 `content`，不能假设一次到齐；空字符串 `""` 不发射文本事件；`null`（工具调用时）忽略。

---

## 5 推理（reasoning）增量——需忽略

两模型在正文前均会流式输出思考过程，结构：

```json
{"choices":[{"delta":{"content":"","reasoning":"The user is asking me","reasoning_details":[{"type":"reasoning.text","text":"The user is asking me","format":"unknown","index":0}]}}]}
```

- `delta.reasoning` + `reasoning_details` 与 `content:""` 同时出现。
- 推理帧数量：mimo 约 10 帧，kimi 约 50+ 帧（更啰嗦）。
- **解析器应完全忽略 `reasoning` / `reasoning_details`，不产生文本事件**。

---

## 6 工具调用 delta 分片形态

两模型均遵循 `delta.tool_calls[ { index, id, type, function: {name, arguments} } ]`，但分片粒度不同：

#### mimo-v2.5（稍大块）

```
帧1: {"tool_calls":[{"index":0,"id":"call_4b1f6a1bfda54f80bb3e3047","type":"function","function":{"name":"read","arguments":""}}]}
帧2: {"tool_calls":[{"index":0,"function":{"arguments":""}}]}
帧3: {"tool_calls":[{"index":0,"function":{"arguments":"{\"path\": "}}]}
帧4: {"tool_calls":[{"index":0,"function":{"arguments":"\""}}]}
帧5: {"tool_calls":[{"index":0,"function":{"arguments":"a"}}]}
帧6: {"tool_calls":[{"index":0,"function":{"arguments":".txt"}}]}
帧7: {"tool_calls":[{"index":0,"function":{"arguments":"\""}}]}
帧8: {"tool_calls":[{"index":0,"function":{"arguments":"}"}}]}
```

#### kimi-k2.6（更细碎，切到键名/符号）

```
帧1: {"tool_calls":[{"index":0,"id":"read_0","type":"function","function":{"name":"read","arguments":""}}]}
帧2: {"tool_calls":[{"index":0,"function":{"arguments":""}}]}
帧3: {"tool_calls":[{"index":0,"function":{"arguments":"{\""}}]}
帧4: {"tool_calls":[{"index":0,"function":{"arguments":"path"}}]}
帧5: {"tool_calls":[{"index":0,"function":{"arguments":"\":\""}}]}
帧6: {"tool_calls":[{"index":0,"function":{"arguments":"a"}}]}
帧7: {"tool_calls":[{"index":0,"function":{"arguments":".txt"}}]}
帧8: {"tool_calls":[{"index":0,"function":{"arguments":"\"}"}}]}
```

**共同点**：

- 首帧携带 `id` + `name` + 空 `arguments`；后续帧仅 `function.arguments` 字符串增量。
- `index` 恒为 0（单工具），`content` 为 `null`。
- 最终帧 `finish_reason: "tool_calls"`。

**解析器结论**：逐帧提取 `tool_calls[].function.arguments` 拼接即得完整参数 JSON，上层拼装；解析器需输出 `ToolCallDelta(index, id, name, argumentsDelta)` 并标记 `suppressed=true`（不外发）。

---

## 7 用量块（usage）

两模型一致，附加在最后 `finish_reason` 帧：

```json
{
  "usage": {
    "prompt_tokens": 268,
    "completion_tokens": 41,
    "total_tokens": 309,
    "prompt_tokens_details": {"audio_tokens":0,"cached_tokens":192,"cache_write_tokens":0},
    "completion_tokens_details": {"audio_tokens":0,"reasoning_tokens":0}
  }
}
```

- `reasoning_tokens` 在 mimo 工具调用为 0，kimi 约 40–100。
- **解析器应忽略 `usage`，不产生事件**；仅作为日志/计费。

---

## 8 各模型差异小结

| 维度 | mimo-v2.5 | kimi-k2.6 | 对解析器影响 |
| ------ | ----------- | ----------- | -------------- |
| 心跳 | 1 次（流首） | 2 次（流首） | 均忽略 |
| 文本分片 | 粗（词/句） | 细（字/标点） | 需累积 |
| 推理帧数量 | ~10 | ~50 | 均忽略 |
| 工具参数分片 | 中等（`{"path":` 为一块） | 极细（`path` / `":"` 拆开） | 均按 `arguments` 拼接 |
| 工具 id | `call_<hex>` | `read_0` | 仅首帧有 |
| 末尾 cost 块 | 有 | 有 | 忽略 |
| 响应总帧数（文本用例） | ~15 | ~110 | 无影响 |

**一致性结论**：两模型在协议层面完全一致，差异仅为分片粒度与推理啰嗦度；同一解析器可兼容。

---

## 9 解析器容错要求（基于实测）

1. **前缀容错**：`data:` 后可选空格，需同时支持 `data: {...}` 与 `data:{...}`，前后空格 trim。
2. **注释/空行**：`:` 开头与空行均为 `Ignored`，不抛异常。
3. **非 JSON**：`data: not-json` 容错为 `Ignored(non-json)`，不中断流。
4. **空 data**：`data:` 空负载为 `Ignored(empty-data)`。
5. **推理帧**：含 `reasoning`/`reasoning_details` 但 `content=""` 的帧，归为 `Ignored(no-delta)`，不产生文本。
6. **`null` content**：工具首帧 `content: null` 忽略，不产生文本。
7. **`finish_reason`**：`stop`/`tool_calls` 空内容帧应转为 `Done`（补充于 `[DONE]`），`usage` 同帧忽略。
8. **`[DONE]` 终结**：精确匹配 `data: [DONE]`，其后所有行（含 `cost`）标记 `after-done` 忽略。
9. **多 `data` 拼接**：同一事件多个 `data:` 行按 SSE 规范以 `\n` 拼接；解析器支持并可拆分为多事件。
10. **跨块拼接**：TCP chunk 可能在行中切断（如 `data: {"content":"hel` + `lo"}`），解析器通过块归一化重组后再按行分发，保证不丢帧。
11. **超长行**：单行 JSON 可超 100KB（长文本），解析器不得截断或 OOM。
12. **额外字段**：`event:`/`id:`/`retry:` 等非 `data` 字段一律 `Ignored`。
13. **工具增量**：`ToolCallDelta` 恒标记 `suppressed`，上层拼装。

以上结论已在 `SseParser` 实现中落地，并由 `SseParserTest` 的 30 个穷举用例覆盖。

---

## 10 原样采样片段（已脱敏，仅保留结构）

### 文本 - mimo

```
: keep-alive

data: {"id":"gen-...","object":"chat.completion.chunk","created":1788849433,"model":"mimo-v2.5","choices":[{"index":0,"finish_reason":null,"delta":{"role":"assistant","content":"","reasoning":"The user is asking me","reasoning_details":[{"type":"reasoning.text","text":"The user is asking me"}]}}]}

data: {"id":"gen-...","object":"chat.completion.chunk","created":1788849433,"model":"mimo-v2.5","choices":[{"index":0,"finish_reason":null,"delta":{"role":"assistant","content":"你好，世界！"}}]}

data: {"id":"gen-...","object":"chat.completion.chunk","created":1788849433,"model":"mimo-v2.5","choices":[{"index":0,"finish_reason":"stop","delta":{"role":"assistant","content":""}}],"usage":{"prompt_tokens":268,"completion_tokens":41,"total_tokens":309}}

data: [DONE]

data: {"choices":[],"cost":"0"}

```

### 工具 - kimi（节选）

```
data: {"id":"gen-...","object":"chat.completion.chunk","created":1788849442,"model":"kimi-k2.6","choices":[{"index":0,"finish_reason":null,"delta":{"role":"assistant","content":null,"tool_calls":[{"index":0,"id":"read_0","type":"function","function":{"name":"read","arguments":""}}]}}]}

data: {"id":"gen-...","object":"chat.completion.chunk","created":1788849442,"model":"kimi-k2.6","choices":[{"index":0,"finish_reason":null,"delta":{"role":"assistant","content":null,"tool_calls":[{"index":0,"function":{"arguments":"{\""}}]}}]}

data: {"id":"gen-...","object":"chat.completion.chunk","created":1788849442,"model":"kimi-k2.6","choices":[{"index":0,"finish_reason":"tool_calls","delta":{"role":"assistant","content":""}}],"usage":{"prompt_tokens":62,"completion_tokens":64,"total_tokens":126}}

data: [DONE]

data: {"choices":[],"cost":"0"}

```

---

## 11 复现指令（脱敏）

```bash
# 已准备 .env（gitignore，不提交）
cp /Users/xmon/Code/IdeaProjects/mini-code/.env .env  # 含 OPENCODE_API_KEY=<REDACTED>, LLM_BASE_URL=https://opencode.ai/zen/go/v1

# 探针为临时 Python 脚本，逻辑等价于：
# POST https://opencode.ai/zen/go/v1/chat/completions
# Header: Authorization: Bearer <REDACTED>, x-opencode-session: <REDACTED>, x-opencode-client: mini-code-spike
# Body: {"model":"mimo-v2.5"|"kimi-k2.6","messages":[...],"stream":true,"tools":[...]}
# 读取响应字节流并按行打印，探针用后已删
```

验证点：`curl -N -H "Authorization: Bearer <REDACTED>" -H "x-opencode-session: <REDACTED>" https://opencode.ai/zen/go/v1/chat/completions -d '{"model":"kimi-k2.6","messages":[{"role":"user","content":"hi"}],"stream":true}'` 可得同构 SSE。
