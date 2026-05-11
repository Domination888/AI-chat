---
name: GPT-SoVITS raw PCM 输出采样率按版本不同，v2Pro/v3/v4 是 48000
description: 后端把 raw PCM 流给前端时必须按推理版本告知正确 sampleRate，否则前端按错误采样率拼 AudioBuffer 会变速变调
type: feedback
---

GPT-SoVITS api_v2 的 `/tts` 返回 raw PCM (media_type=raw) 时，PCM 的采样率取决于推理 version：
- v1 / v2          → 32000 Hz
- v2Pro / v2ProPlus → **48000 Hz**
- v3                → 24000 Hz
- v4                → 48000 Hz（实测 wav header 也是 48k）

**Why:** 之前后端 `AudioController.emitTts` 注释里写 "v2/v2Pro/v4 = 32000Hz" 是错的，导致用 v2Pro 推理时前端按 32k 解码 48k PCM → 播放速度变 2/3、音调降低半音以上 → 用户听感"声音完全不像 + 很慢"，但其实模型推理本身正常。靠 `curl -X POST /tts media_type=wav` 出来的 wav header 才确认了真实采样率。

**How to apply:**
- 切换 `tts_infer.yaml` `custom.version` 时，必须**同步**修改 `AudioController.java` `chunkStart` 包里的 `sampleRate` 字段以及 [`frontend/src/App.vue`](frontend/src/App.vue:590) 的兜底默认值
- 验证方法：`curl -X POST .../tts -d '{...media_type:"wav"...}' -o x.wav && file x.wav` 看 sample rate
- 切版本前先在记忆里查这个表，再决定后端/前端要发什么 sampleRate