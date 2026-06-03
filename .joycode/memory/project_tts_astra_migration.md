---
name: tts-astra-migration
description: TTS 已从 Mac 本机 GPT-SoVITS/MLX-Audio 迁移到 Win Astra/Genie-TTS
type: project
---

TTS 引擎已从 Mac 本机（GPT-SoVITS / MLX-Audio）迁移到 Win 上的 Astra (Genie-TTS) 服务 (:5000)。

**Why:** Astra TTS 部署在 Win CPU 上推理，不占 GPU 显存，Mac 端不再需要跑 TTS。

**How to apply:**
- 后端 TTS 唯一引擎为 `astra`，调用 Win `192.168.124.2:5000/api/tts/predict-stream` GET 流式接口
- 输出格式：IEEE float32 LE PCM（pcm_f32le），单声道，采样率 32000Hz
- 前端通过 SSE 事件中的 `sampleRate` 和 `format` 字段动态适配，`pcmFloat32ToAudioBuffer` 直接灌 AudioBuffer
- 音色通过 avatarId 选择：`Shu_v2proplus`（黍）、`chenxing`（晨星）
- Mac 端不再有本地 TTS 服务进程，start-tts.sh 仅做 Win 端健康检查