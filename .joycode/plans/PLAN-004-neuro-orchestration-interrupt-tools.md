# PLAN-004 · 类 Neuro 编排核心（打断 / 上下文 / Tools / Live2D / 多源输入）

> 上层目标：[`PLAN-000`](.joycode/plans/PLAN-000-product-overview-neuro-like-ai.md:1)
> 前置依赖：[`PLAN-001`](.joycode/plans/PLAN-001-ai-chat-role-play-refactor.md:1) M2 跑通；[`PLAN-002`](.joycode/plans/PLAN-002-sensevoice-asr-deployment.md:1)、[`PLAN-003`](.joycode/plans/PLAN-003-gpt-sovits-streaming-tts.md:1) 已部署
> 硬件约束：[`.joycode/rules/00-hardware-and-deployment.md`](.joycode/rules/00-hardware-and-deployment.md:1)
> 参考实现：[`my-neuro-main/live-2d/js/ai/`](my-neuro-main/live-2d/js/ai)（重点 [`InputRouter.js`](my-neuro-main/live-2d/js/ai/conversation/InputRouter.js:1)、[`llm-handler.js`](my-neuro-main/live-2d/js/ai/llm-handler.js:1)、[`tts-playback-engine.js`](my-neuro-main/live-2d/js/voice/tts-playback-engine.js:1)）

## Task Summary

把 M2 的"按一下说一句"升级成 Neuro-sama 那种"实时听-边说边想-能被打断-会调工具-演得像"的体验。本 PLAN 不动 ASR/TTS 服务本身，只做**编排层**：会话状态机、输入路由、句级流水线、双向打断、工具调用、Live2D 联动、多源合流。

**核心结论先写在前面，避免再次混淆**：

1. **LLM 没有"流式输入"**：主流 LLM 输入必须一次性给完，输出才能流式。Neuro 走的是 **"流式打断 + 重发完整 prompt + 输出按句切 TTS"** 的工程派方案。
2. **拼 prompt = 每次重新拼**：人设 + 滑窗 + RAG + 工具清单 + 当前输入，**每次新输入到来都重拼**，靠 llama.cpp 的前缀 KV Cache 命中加速，业务层不操心。
3. **打断是双向的**：后端 cancel LLM 请求 + 关闭到 GPT-SoVITS 的 chunked 连接；前端 stop MediaSource + 清队列。两边都要做才不会有"幽灵声音"。
4. **句级流水线是低延迟的灵魂**：LLM token 流 → 标点切句 → 每句独立去调 TTS → chunk 队列保序播放。**不要等整段说完再合成。**

---

## 架构图（编排层）

```
                ┌────────── 多源输入 ──────────┐
                │ 语音 (final 文本)             │
                │ 弹幕 (B 站 WS)                │
                │ 自言自语 tick (定时器)        │
                └─────────────┬─────────────────┘
                              ▼
                       InputRouter
                  (优先级 voice > danmaku > auto)
                              │
                              ▼
              ┌───── ConversationOrchestrator ─────┐
              │ SessionStateMachine                │
              │   IDLE / LISTENING / THINKING /    │
              │   SPEAKING / INTERRUPTED           │
              │                                    │
              │ 收到新输入 →                        │
              │   1) cancel currentLlmTask         │
              │   2) close currentTtsStream        │
              │   3) WS push {type:flush_audio} 给前端
              │   4) 把已说的 partial 文本截断回灌到对话历史
              │   5) PromptAssembler 拼新 prompt    │
              │   6) 启动新的 LlmTask               │
              └────────────────┬───────────────────┘
                               ▼
                  LlmStream (LM Studio SSE)
                               │
                               ▼
                ┌────── token 处理流水线 ──────┐
                │ 1. 思考标签缓冲 <think>...</think>（不送 TTS）
                │ 2. 工具调用 JSON 缓冲 → ToolExecutor → 结果回灌再发一轮 LLM
                │ 3. 情感标签 [EMO:happy] 提取并剥离
                │ 4. SentenceSplitter 按标点切句
                │ 5. 每句产出 (sentenceId, text, emotion)
                └────────────────┬─────────────┘
                                 ▼
                       TtsDispatcher
                   (并发 N 句，但前端按 seq 保序播)
                                 │
                                 ▼
                   GPT-SoVITS Streaming
                                 │
                                 ▼
              WS 二进制 chunk (sentenceId 标记)
                                 │
                                 ▼
              前端 TtsPlaybackQueue (按 seq 保序)
                                 │
                                 ▼
                ┌────────────┴───────────┐
                ▼                        ▼
        Audio MediaSource         Live2D 触发
        边收边播                  (expression+motion+mouth RMS)
```

---

## TODO: 阶段 0 · 概念对齐 + 端到端时序图

- [ ] 在 [`PLAN-000`](.joycode/plans/PLAN-000-product-overview-neuro-like-ai.md:1) 附录补一张 draw.io 时序图，标记打断点 / 切句点 / 上下文注入点
- [ ] **验收**：用户 review 时序图通过

## TODO: 阶段 1 · 后端会话编排核心

- [ ] 新建包 `com.aichat.conversation`：
  - `SessionContext`（每用户一份）：人设、滑窗、当前 `LlmTask` 句柄、当前 `TtsStream` 句柄、状态机
  - `InputEvent { source: voice|danmaku|auto, text, ts, user? }`
  - `LlmTask`（封装一次 LLM SSE 调用，持有 `Disposable`，`cancel()` 可幂等）
  - `TtsStream`（封装一次 GPT-SoVITS 流，`abort()` 主动断 chunked）
- [ ] 实现 `InputRouter`（参考 [`InputRouter.js`](my-neuro-main/live-2d/js/ai/conversation/InputRouter.js:1)）：
  - 优先级队列：voice > danmaku > auto
  - 高优先级到来立刻打断低优先级
- [ ] 实现 `SessionStateMachine`：`IDLE / LISTENING / THINKING / SPEAKING / INTERRUPTED`
- [ ] **验收**：用 mock 输入跑单测，状态切换正确

## TODO: 阶段 2 · PromptAssembler

- [ ] 输入：`SessionContext + InputEvent + ToolRegistry`
- [ ] 输出：完整 messages 数组：
  ```
  [
    {role: system, content: 人设 + 直播规则 + 工具说明}
    {role: system, content: RAG Top-3 长期记忆}
    ...recentTurns (滑窗 N=10)
    {role: user, content: "[来源:voice] 你好啊"}
  ]
  ```
- [ ] 工具说明部分：把 `ToolRegistry` 注册的函数生成 OpenAI tool-calling schema 注入
- [ ] **验收**：单测看拼出来的 messages 结构正确，token 数在阈值内

## TODO: 阶段 3 · LLM 流式 + 句级切分 + 思考/工具标签处理

- [ ] 用 LangChain4j `StreamingChatLanguageModel` 调 LM Studio（`stream=true`）
- [ ] 后端做 `TokenProcessor`，逐 token 处理：
  - **思考标签**：`<think>...</think>` 之间的内容缓冲，**不切句、不送 TTS**，通过 WS 单独推 `{type: thinking, text}` 给前端展示"她在想"
  - **工具调用**：检测 OpenAI tool-call delta（`tool_calls`），等到完整 JSON → 走 ToolExecutor → 结果以 `tool` role 回灌历史 → **再发一轮 LLM**（典型 ReAct 循环）
  - **情感标签**：约定 LLM 在每句后追加 `[EMO:happy|sad|angry|...]`，提取出来剥离再送 TTS，emotion 单独 WS 推给前端给 Live2D 用
  - **正常文本**：进 `SentenceSplitter`
- [ ] `SentenceSplitter` 规则（参考 [`llm-handler.js`](my-neuro-main/live-2d/js/ai/llm-handler.js:1)）：
  - 命中 `。！？!?；…\n` 立即出句
  - 首句阈值放低（>= 8 字 + 命中标点也出，让用户更快听到第一声）
  - 后续句阈值 >= 20 字
  - 兜底：> 60 字硬切
- [ ] 每出一句：`(sentenceId, text, emotion)` 入 `TtsDispatcher`
- [ ] **验收**：发 "你好[EMO:happy]。今天天气真好[EMO:happy]。" → 后端日志看到两次 `onSentence`，emotion 字段被剥离

## TODO: 阶段 4 · TtsDispatcher + 流式 chunk 转发（GPT-SoVITS 联调）

- [ ] `TtsDispatcher`：接 `(sentenceId, text)`，并发去调 PLAN-003 部署的 GPT-SoVITS（注意：并发上限 1~2，否则 Mac 内存爆）
- [ ] 拿到 `Flux<DataBuffer>` 后，**给每个 chunk 加 sentenceId 标记**，通过 WebSocket 二进制帧推前端：
  - 控制帧 JSON：`{type: tts_start, sentenceId, emotion}`
  - 二进制帧：前缀 4 字节 sentenceId + chunk 数据
  - 控制帧 JSON：`{type: tts_end, sentenceId}`
- [ ] 维护 `currentTtsStreams: Map<sentenceId, Disposable>`，被打断时全部 `abort()`
- [ ] **验收**：发一段 3 句话，能在前端日志看到 3 个 sentenceId 的 chunk 流交错到达，但顺序由 sentenceId 决定

## TODO: 阶段 5 · 前端流式播放队列（保序 + 可打断）

- [ ] 升级 PLAN-003 的 `StreamingMp3Player` 为 `TtsPlaybackQueue`（参考 [`tts-playback-engine.js`](my-neuro-main/live-2d/js/voice/tts-playback-engine.js:1)）：
  - 每个 sentenceId 对应一个独立 `MediaSource + SourceBuffer`，预先建好
  - 收到 `tts_start` → 预创建该 seq 的 buffer
  - 收到对应 chunk → `appendBuffer`
  - 收到 `tts_end` → 标记该 seq 完成
  - 播放器单线程串行：seq 1 播完才播 seq 2，即使 seq 2 数据先齐了也等
  - 收到 `flush_audio` → 全部停 + 清空
- [ ] 触发 Live2D 表情：sentenceId 开始播放时 `emit('emotion', emo)`
- [ ] 嘴型同步：`AnalyserNode` 取 RMS → 喂 `ParamMouthOpenY`
- [ ] **验收**：连续 5 句顺序对、打断 200ms 内静音、嘴动跟得上

## TODO: 阶段 6 · 双向打断（barge-in）

- [ ] 前端：TTS 播放中持续运行 VAD（Silero VAD ONNX，小巧 1.8MB），检测到用户开口 > 200ms：
  - 立即 WS 发 `{type: interrupt}` 给后端
  - 立即 `playbackQueue.flush()` 停声
  - 进入新一轮录音
- [ ] 后端收到 `interrupt`：
  - `currentLlmTask?.cancel()`
  - `currentTtsStreams.values().forEach(d -> d.abort())`
  - 把"已经吐给前端但还没播完的句子"标记为"未说完"，**只把已确认播放完的句子写进对话历史**（避免幻觉）
  - 状态机进 `INTERRUPTED` → 等下一个 `voice.final` 事件
- [ ] AEC：依赖浏览器 `getUserMedia({echoCancellation:true})`；TTS 播放时把 VAD 触发阈值上调 0.3 防自激
- [ ] **验收**：AI 说话中途插话 → 300ms 内停声 → 新输入被处理

## TODO: 阶段 7 · Tools 函数调用

- [ ] `ToolRegistry`：内置注册几个工具
  - `query_weather(city)`
  - `play_live2d_motion(motionName)`
  - `query_rag(question)`（强制走 RAG 库）
  - `write_diary(content)`
  - 后续可挂 MCP（参考 [`prime-mcp-server`](prime-mcp-server)）
- [ ] `ToolExecutor`：拿到 `tool_calls` JSON → dispatch 到对应 handler → 拿到结果 → 以 `role=tool` 写回 messages → 再发一轮 LLM
- [ ] 注意：LM Studio 对 function calling 支持取决于模型，**Gemma3 原生不支持 OpenAI tool-call**，需要：
  - 方案 A：手搓 prompt 协议，让 LLM 输出固定 JSON `<tool>...</tool>`，后端正则解析
  - 方案 B：换支持 tool 的模型（Qwen2.5、Llama3.1 instruct）
  - 方案 C：用 LM Studio 自带的 tool-call 适配（最新版本支持）
  - 这个选择留到动手时根据 LM Studio + Gemma3 实测决定
- [ ] **验收**：让她"查一下北京天气" → LLM 触发 tool → 后端调 mock 接口 → 结果回灌 → 她说出最终答案

## TODO: 阶段 8 · Live2D 联动

- [ ] 表情映射：emotion 字符串 → expression3.json 索引（参考 [`emotion-expression-mapper.js`](my-neuro-main/live-2d/js/ui/emotion-expression-mapper.js:1)）
- [ ] 动作映射：emotion → motion 组（开心组、生气组）
- [ ] 嘴型：每个 sentenceId 播放期间，从对应 audio 元素接 `AnalyserNode` 取 RMS → `model.internalModel.coreModel.setParameterValueById('ParamMouthOpenY', rms*4)`
- [ ] **验收**：开心句模型笑、生气句模型撇嘴、嘴动幅度跟音量

## TODO: 阶段 9 · 多源输入合流

- [ ] 弹幕源：B 站直播 WS 客户端（参考 my-neuro-main 的 [`bilibili-live`](my-neuro-main/live-2d/plugins/built-in/bilibili-live) 插件）→ `InputEvent(source=danmaku, user, text)`
- [ ] 自言自语：定时 30~120s 投 `InputEvent(source=auto, text="<空，让她自由发挥>")`
- [ ] 优先级处理已在阶段 1 `InputRouter` 实现，这里只接源
- [ ] **验收**：模拟同时进语音 + 弹幕，语音先；播 auto 时来弹幕能正确打断

## TODO: 阶段 10 · 上下文增强（让她记得住）

- [ ] 滑窗：`recentTurns` = 10
- [ ] 压缩器：每超 N 轮调一次 LLM "请把以上对话浓缩成 200 字"，存入向量库
- [ ] RAG 召回：每次新输入 → embedding → 向量库 Top-3 → 注入 prompt（[`PromptAssembler`](.joycode/plans/PLAN-004-neuro-orchestration-interrupt-tools.md) 阶段 2 已留位置）
- [ ] 日记：每天定时让 LLM 总结当日重点 → 落 MySQL `diary` 表（参考 [`DiaryManager.js`](my-neuro-main/live-2d/js/ai/DiaryManager.js:1)）
- [ ] **验收**：连续 50 轮后能正确回忆第 5 轮细节

---

## 端到端延迟预算

| 环节 | 位置 | 目标延迟 |
| --- | --- | --- |
| 用户说完到 ASR final | Mac | < 1.5s |
| Prompt 拼装 + Mac→Win 网络 | | < 100ms |
| LLM 首 token | Win | < 800ms |
| 首句切出 + GPT-SoVITS 首 chunk | Mac | < 800ms |
| **端到端首声** | | **< 3.5s** |

## 验收标准（整体）

- [ ] 端到端首声 < 3.5s
- [ ] 打断响应 < 300ms（前端停声）
- [ ] 工具调用闭环 OK（一个 mock 工具能跑通）
- [ ] 30 分钟连续聊天不串戏、不忘人设
- [ ] Live2D 表情 / 嘴型与语音同步无明显错位
- [ ] 三源输入（voice / danmaku / auto）共存，优先级正确

## 风险与回退

1. **Gemma3 不支持 tool calling** → 阶段 7 三个方案中挑一个
2. **句切错切**（中文标点缺失） → 60 字硬切兜底
3. **GPT-SoVITS 流式延迟差** → 短期回退 edge-tts
4. **Mac 32GB 不够** → SenseVoice 切 CPU、压上下文长度、关 RAG
5. **VAD 自激（AI 说话被自己 VAD 误触发打断）** → AEC + 阈值动态调高

## 不做的事

- 不做手机端 / 桌面端原生 App，只 Web
- 不做声纹识别 / 多说话人区分
- 不做超长记忆（年级别），只做"近 30 天 + 摘要"