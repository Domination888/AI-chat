# PLAN-010: 对话打断 + 主动搭话 + 前端配置

## 任务摘要

实现三个紧密关联的功能：
1. **对话打断** — 用户新说一句话时，立即取消上一轮的 LLM 流式生成和 TTS 音频播放，转而处理最新输入
2. **主动搭话** — 参照 my-neuro 的 `AutoChatModule`，当用户一段时间没说话时，AI 主动发起对话并走 TTS 语音回放
3. **前端配置** — 在 SettingsModal 中增加"主动说话频率"等可调参数

---

## TODO 1: 对话打断机制

### 后端 — SSE 流取消信号

- [ ] 新增 `POST /api/chat/interrupt` 接口：接收 `{conversationId, userId}`，通过 `ConcurrentHashMap<String, Sinks.Many>` 维护每个 conversationId 的当前 sink，打断时调用 `sink.tryEmitComplete()` 终止 SSE 流
- [ ] 在 `ChatController.chat()` 中注册 sink 到全局 sinkRegistry，在 `doOnComplete/doOnError` 时自动移除
- [ ] `ChatServiceImpl.chatStream()` 的 FluxSink 需要在 `onCancel` 回调中停止 LLM 流式生成（langchain4j StreamingChatResponseHandler 无法直接取消，需用 `AtomicBoolean cancelled` 标记，`onPartialResponse` 中检查标记跳过输出）

### 前端 — 请求级打断

- [ ] `sendMessage()` 和 `sendAudio()` 入口处：如果 `loading.value === true`，先调 `abortCurrentChat()` 再发新请求
- [ ] `abortCurrentChat()` 实现：① 调 `POST /api/chat/interrupt` 通知后端 ② 用 `AbortController` 取消当前 fetch SSE 流 ③ 清空 `audioQueue` + 停止 `currentSource` ④ 清理当前 AI 消息气泡（保留已收到文本或标记"[已打断]"）
- [ ] `doChatSSE()` 增加 `AbortController` 参数，fetch 请求传 `signal`；流被 abort 时 catch AbortError 优雅退出
- [ ] TTS 音频打断：`abortCurrentChat()` 中调 `stopCurrentAudio()`（停止 source + 清空 audioQueue + 重置 `audioPlaying`）

---

## TODO 2: 主动搭话（Auto-Chat）

### 后端 — 主动对话服务

- [ ] 新增 `ProactiveChatService`：用 `ScheduledExecutorService` 按配置的 idle 间隔检查"用户最后交互时间"，超时则自动生成一条 LLM 回复并通过 SSE 推送给前端
- [ ] 新增 `POST /api/chat/proactive` 接口：前端连上后主动注册 `conversationId + userId + roleId`，后端开启定时器；前端断开时调 `DELETE /api/chat/proactive` 停止
- [ ] 主动对话的 LLM 请求：message 字段填充系统指令 `[System: 用户长时间未说话，请根据上下文主动搭话，自然地延续对话]`，走正常 `chatStream` 流程，SSE 事件加一个 `event: proactive` 标记让前端区分
- [ ] 用户发新消息时：`ProactiveChatService.updateLastInteraction(conversationId)` 重置 idle 计时器；如果正在生成主动回复，也触发打断逻辑

### 前端 — 主动对话消费

- [ ] 登录后/选会话后，调 `POST /api/chat/proactive` 注册；切换会话/退出时调 DELETE
- [ ] 收到 `event: proactive` SSE 事件时：在 messages 中新增一条 `role: 'ai'` 消息（标记 `isProactive: true`），后续 text/tts 事件照常填充
- [ ] 主动对话的 TTS 音频同样走 `handleTtsEvent` + `pumpAudioQueue` 播放
- [ ] 用户自己说话时：前端先打断正在进行的主动对话（调 abortCurrentChat），再发新请求

---

## TODO 3: 前端配置界面

- [ ] SettingsModal.vue 新增"主动说话"区块：
  - 开关：`proactiveChatEnabled` (boolean, default true)
  - 空闲时间滑块：`proactiveIdleSeconds` (range 5-120s, default 30s)
  - 提示词输入框：`proactivePrompt` (textarea, default: `[System: 请根据上下文自然搭话]`)
- [ ] 保存时写入 localStorage + 发送给后端（`POST /api/chat/proactive/config` 或随注册请求一起传）
- [ ] App.vue `settings` 对象扩展对应字段，注册 proactive 时带上配置参数

---

## 验证步骤

- [ ] V1 验证：用户正在听 AI 回复时发新消息 → 旧回复立即停止、TTS 停止、新回复正常开始
- [ ] V2 验证：用户 30s 不说话 → AI 自动搭话、语音播放正常
- [ ] V3 验证：主动说话进行中用户又说话 → 主动回复被打断、用户新回复正常
- [ ] V4 验证：前端关闭 proactive 开关 → AI 不再主动搭话；改频率 → 间隔随之变化