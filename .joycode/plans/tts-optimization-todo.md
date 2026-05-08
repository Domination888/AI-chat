# TTS 流式对话 下次优化 TODO

## 背景
语音链路目前可以打通：ASR → LLM 流式 → 按句切分 → GPT-SoVITS 合成 → 前端播放。
但遗留两个问题本次不修，下次迭代处理。

---

## 问题 1：TTS 耗时过长

### 现象
单句合成 ~4 秒音频需要 ~50s（首次冷启）、后续句子也在数秒量级，用户体感非常慢。

### 已知成因（按影响面排序）
1. **v4 模型 + Apple MPS 推理慢**
   - v4 LoRA + BigVGAN vocoder + Per-Layer Embedding，单 turn 计算量比 v2 大数倍
   - M4 MPS 对部分 Conv / Attention 算子支持不全，会回落 CPU
2. **句子级串行合成**
   - 当前实现 [`AudioController.emitTts`](../../src/main/java/org/example/aichat/controller/AudioController.java) 是每句等完整 wav 回来再发 SSE，没有真正把 GPT-SoVITS 的 streaming chunk 边收边推给前端
3. **参考音频每次都重新编码**
   - 参考音频路径没有 cache，GPT-SoVITS 每次 `/tts` 都会重算 prompt embedding

### 下次要做的事
- [ ] 改造 [`VoiceServiceImpl.ttsStream`](../../src/main/java/org/example/aichat/service/impl/VoiceServiceImpl.java) —— 改成 **真·流式**：不在 Java 端缓存完整 wav，chunk 到就 base64 后立刻推 SSE（前端侧对应改 MediaSource 拼接播放）
- [ ] `application-local.yml` 加可切档：`voice.tts-streaming-mode: 3`（更快更糙）先做个对照基线
- [ ] 研究 GPT-SoVITS `ref_cache` / `inp_refs` 缓存机制，减少参考音频重算
- [ ] 评估切回 v2 模型 + 黍角色重训，单纯是否可接受速度明显上升（v2 无 vocoder，MPS 友好很多）
- [ ] 再不行就上 `cut` 前端预合成：第一句开始合成时就把 greeting 的第二、三句预先推进队列
- [ ] 打一组性能基线：记录 token 数、文字长度、合成耗时、首包时间（TTFB），对比不同配置

---

## 问题 2：合成后前端没播放声音

### 现象
后端日志正常产出 `TTS ok: bytes=NNN cost=Xms`，SSE `tts` 事件也推出去了，但前端气泡正常刷，**语音不响**。

### 可能成因（按排查优先级）
1. **浏览器 Autoplay Policy 拦截**（最可能）
   - 首次 audio.play() 在**没有用户手势**的上下文中会被 Chrome/Safari 静默拦截
   - 语音模式的 `@mousedown` 录音是手势，但手势在 recorder 流程里已经被消费；到了 stream 回来时 `new Audio(url).play()` 相当于异步后续操作，有的浏览器会拒绝
2. **base64 解码时机**
   - [`App.vue` enqueueAudioBase64](../../frontend/src/App.vue:487) 把 base64 → Blob → ObjectURL → Audio，中间某一步可能 Blob 的 MIME 不对
   - 当前写的 `new Blob([bytes], { type: 'audio/wav' })` 理论上 OK，但 GPT-SoVITS streaming_mode=2 发过来的 wav 其实是**多个 wav 片段拼接**（多个 RIFF header），某些浏览器只能播第一个
3. **audioQueue 竞争**
   - `audioPlaying` 是模块级 let，没有锁，多次快速 enqueue 可能重复触发或被吞

### 下次要做的事
- [ ] 前端先在 devtools 里看：
  - SSE 确实收到了 `event:tts` 事件，`audioBase64` 非空
  - `new Audio(url).play()` 的 Promise 是否 reject（打 catch 日志，很多时候是 NotAllowedError）
- [ ] 加一个 **"播放"解锁按钮**：用户主动点一次后，全局置一个 `audioUnlocked=true`，之后 audio.play() 才去跑
- [ ] 或者改用 `AudioContext + decodeAudioData` 播放 PCM chunk（比 `<audio>` 灵活，能直接解 wav 数据）
- [ ] 把多次 SSE 的 wav 片段先在前端**拼装**成单个 Blob 再播（或者服务端保证整段合成完一次发），避免 streaming wav 多 header 问题
- [ ] 在 UI 上加一个"当前正在播：句子 X"的 indicator，方便调试
- [ ] 兜底：前端加 `<audio controls>` 挂在气泡下面，让用户能手动点按钮强制播放当前句

---

## 相关文件索引
- 后端
  - [`VoiceServiceImpl`](../../src/main/java/org/example/aichat/service/impl/VoiceServiceImpl.java) — HttpClient、流式 chunk 回调
  - [`AudioController`](../../src/main/java/org/example/aichat/controller/AudioController.java) — SSE 事件流、`emitTts`、句子切分
  - [`SentenceSplitter`](../../src/main/java/org/example/aichat/service/SentenceSplitter.java) — 句子切分策略（可能也需要调 minLen/maxLen）
  - [`VoiceProperties`](../../src/main/java/org/example/aichat/config/VoiceProperties.java) — `tts-streaming-mode`、`tts-profiles`
- 前端
  - [`App.vue` sendAudio / pumpAudioQueue / enqueueAudioBase64](../../frontend/src/App.vue)
- 配置
  - [`application-local.yml`](../../src/main/resources/application-local.yml) `voice.*` 段
- GPT-SoVITS 侧
  - [`v4/启动tts.sh`](../../v4/启动tts.sh)
  - [`GPT-SoVITS/GPT_SoVITS/configs/tts_infer.yaml`](../../GPT-SoVITS/GPT_SoVITS/configs/tts_infer.yaml) — device=mps / is_half=false

## 验收标准（下次完成时）
- 首句 TTFB ≤ 3s，后续句 ≤ 1.5s
- 前端 100% 能听到声音，不用手动点按钮
- 长回答（5 句以上）语音不断、不漏句