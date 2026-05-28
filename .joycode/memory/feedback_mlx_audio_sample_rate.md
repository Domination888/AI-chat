---
name: mlx-audio-qwen3-tts-sample-rate
description: MLX-Audio + Qwen3-TTS 输出采样率 24kHz，与 GPT-SoVITS v2Pro 的 48kHz 不同，切换引擎时前端必须动态适配
type: feedback
---

MLX-Audio + Qwen3-TTS 输出采样率为 24kHz，GPT-SoVITS v2Pro 为 48kHz。
**Why:** 不同 TTS 引擎/模型版本的原始 PCM 输出采样率不同，前端按错误采样率拼 AudioBuffer 会变速变调。
**How to apply:** 后端 TTS 接口返回时必须附带当前引擎的 sampleRate，前端据此创建 AudioContext，引擎切换时动态更新。