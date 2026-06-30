# 延迟日志字段说明

延迟日志用于观察一次对话从前端发起、后端处理、LLM 流式返回、TTS 生成，到前端播放完成的全链路耗时。

日志文件默认写在：

```text
unified-logs/backend/latency.log
```

后端只保留最近 30 次请求。每次 SSE 结束时会先写入一版服务端步骤；如果前端随后上报了同一个 `TraceId` 的播放侧时间，日志会用合并后的完整条目替换原条目。

## 顶部字段

| 字段 | 含义 |
| --- | --- |
| `Time` | 这条日志写入或更新的本机时间。 |
| `TraceId` | 单次请求的短链路 ID。前端收到 `done` SSE 后用它回传播放侧延迟。 |
| `ConversationId` | 会话 ID，用于关联某个聊天窗口或主动搭话会话。 |
| `InputMode` | 输入模式。常见值：`text` 文本输入、`audio` 语音输入、`proactive` 主动搭话。 |
| `Meta.*` | 请求附加信息。`Meta.wantTts` 表示是否请求 TTS；`Meta.ttsSentences` 表示后端生成了多少句 TTS；`Meta.source: proactive` 表示主动搭话；`Meta.error` 表示该请求异常结束。 |

## 时间列

步骤表头如下：

```text
Step                          Abs(ms)     Span
```

| 列 | 含义 |
| --- | --- |
| `Step` | 打点名称。 |
| `Abs(ms)` | 相对锚点的毫秒偏移。优先以 `client_sent` 为 0；没有前端发送时间时，以 `request_received` 为 0。 |
| `Span` | 当前步骤与上一条已记录步骤之间的差值，单位毫秒。它按字段插入顺序计算，不按时间戳重新排序。 |

注意：`tts_last_done` 会被每句 TTS 完成时反复更新，但字段位置仍停留在第一次插入的位置。因此后续 `tts_1_start`、`tts_2_start` 附近可能出现负数 `Span`。这种负数通常表示“字段被更新后顺序没有重排”，不是时间倒流。看 TTS 总耗时优先看底部 `TTS (首句→末句生成)`。

## 后端步骤

| Step | 含义 |
| --- | --- |
| `request_received` | 后端 `/api/chat` 接收到请求并创建 trace 的时间。 |
| `asr_start` | 语音输入时，后端开始 ASR 识别。仅 `InputMode=audio` 出现。 |
| `asr_done` | 语音输入时，ASR 识别完成并得到文本。仅 `InputMode=audio` 出现。 |
| `context_memos` | 会话、历史消息、角色提示、Memos 长期记忆等上下文组装到这一阶段完成。 |
| `context_pre_search` | 技能预取和通用联网预搜索阶段完成。是否真的联网取决于请求开关和跳过策略。 |
| `context_rag` | 本地 RAG 检索和注入阶段完成。 |
| `llm_prompt_ready` | 发给 LLM 的消息列表、工具规格等准备完成。 |
| `llm_request` | 第一次向 LLM 发起流式请求。 |
| `llm_first_token` | 后端收到 LLM 的第一个 token。可用于判断模型首 token 延迟。 |
| `tool_round_N_start` | 第 N 轮工具调用开始。只有 LLM 触发 MCP/tool call 时出现。 |
| `tool_round_N_done` | 第 N 轮工具调用完成。只有 LLM 触发 MCP/tool call 时出现。 |
| `llm_complete` | LLM 当前完整流式回复结束。被打断或超时时可能缺失。 |
| `sse_first_text` | 后端第一次向前端发送非空 `text` SSE。情绪标签会先被剥离，所以它不一定等同于 `llm_first_token`。 |
| `tts_N_start` | 第 N 句 TTS 开始生成，`N` 从 0 开始。 |
| `tts_N_first_chunk` | 第 N 句 TTS 的首个音频 chunk 返回。`tts_N_first_chunk - tts_N_start` 是该句 TTS 首包耗时。 |
| `sse_first_tts_chunk` | 后端第一次向前端发送 TTS 音频 chunk。通常等于某个 `tts_N_first_chunk`。 |
| `tts_N_done` | 第 N 句 TTS 生成完成，并向前端发送该句 `chunkEnd`。 |
| `tts_last_done` | 当前已完成的最后一句 TTS 的完成时间。每句完成都会更新这个字段。 |
| `sse_done` | 后端发送 `done` SSE 前后的服务端结束点；异常时也会在落盘前标记。 |

## 前端步骤

这些字段由前端在 SSE 结束并且 TTS 播放侧条件满足后，通过 `/api/chat/latency` 回传。

| Step | 含义 |
| --- | --- |
| `client_sent` | 用户点击发送或触发请求时的前端时间，也是完整链路的首选 0 点。 |
| `client_fetch_start` | 前端开始发起 `fetch('/api/chat')` 的时间。通常和 `client_sent` 很接近。 |
| `client_response_headers` | 前端拿到 HTTP 响应头的时间。它反映连接、网关、后端开始返回 SSE 之前的等待。 |
| `client_first_sse` | 前端解析到第一条 SSE 事件的时间。事件可以是 `asr`、`text`、`tts`、`done` 或 `error`。 |
| `client_asr_text` | 语音输入时，前端收到 ASR 文本事件并回填用户消息的时间。 |
| `client_first_text` | 前端收到第一段非空 AI 文本 delta 的时间。 |
| `client_first_tts_chunk` | 前端收到并解码第一段 TTS 音频 chunk 的时间。 |
| `client_first_tts_play` | 前端实际开始播放第一段 TTS 音频并启动口型同步的时间。 |
| `client_sse_done` | 前端确认 SSE `done` 后、准备上报延迟时的时间。 |
| `client_last_tts_play` | 前端认为最后一句 TTS 实际播放完成的时间。无 TTS 或没有 TTS 句子时，会在 SSE 完成时补这个字段。 |

## 底部汇总

| 汇总项 | 计算方式 | 用途 |
| --- | --- | --- |
| `E2E (client→播放完)` | `client_last_tts_play - client_sent` | 用户从发送到听完回复的完整体感耗时。只有前端播放上报成功后出现。 |
| `E2E (client→SSE结束)` | `sse_done - client_sent` | 从前端发送到服务端流结束。 |
| `Server (收请求→SSE结束)` | `sse_done - request_received` | 后端处理、LLM、TTS 生成和 SSE 输出的总耗时。 |
| `LLM (发请求→首token)` | `llm_first_token - llm_request` | 模型首 token 延迟。 |
| `LLM (发请求→完成)` | `llm_complete - llm_request` | 模型完整回复生成耗时，含工具循环后的整体流式过程。 |
| `TTS (首句→末句生成)` | `tts_last_done - tts_0_start` | TTS 从第一句开始到最后一句生成完成的耗时。 |
| `Playback (首播→末播)` | `client_last_tts_play - client_first_tts_play` | 前端实际音频播放耗时。只有前端播放上报成功后出现。 |

## 常见判断方式

| 现象 | 重点看 |
| --- | --- |
| 点发送后很久才有响应 | `client_response_headers`、`request_received`、`context_*`。 |
| 后端收到请求后很久才出字 | `llm_request` 到 `llm_first_token`，以及 `context_memos`、`context_pre_search`、`context_rag`。 |
| 首字已出但前端很晚看到文字 | `sse_first_text`、`client_first_text`。 |
| TTS 首包慢 | `tts_N_start` 到 `tts_N_first_chunk`，尤其是 `tts_0_*`。 |
| 服务端生成完但播放完很慢 | `Playback (首播→末播)` 和 `client_last_tts_play`。 |
| 只有服务端字段，没有前端字段 | 前端未收到 `done`、播放未结束、上报失败，或超过后端 60 秒等待窗口后才上报。 |
| 出现 `Meta.error` | 该请求异常结束，通常只保留异常前已经打到的步骤。 |

