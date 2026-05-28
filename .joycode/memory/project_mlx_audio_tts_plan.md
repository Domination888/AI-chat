---
name: mlx-audio-tts-deployment-plan
description: >-
  已制定 MLX-Audio + Qwen3-TTS 部署方案 (PLAN-012)，部署到 services/mlx-audio-tts/，保留
  GPT-SoVITS 可切换
type: project
---

PLAN-012 已制定：在 Mac M4 上部署 MLX-Audio + Qwen3-TTS 替代 GPT-SoVITS 作为主力 TTS。
**Why:** GPT-SoVITS PyTorch MPS 兼容性差、TTFB 1-3s、无原生流式；MLX-Audio 利用 Apple Silicon Metal 原生加速，TTFB ~200ms，原生流式。
**How to apply:** 服务部署在 services/mlx-audio-tts/，端口 9881，OpenAI 兼容 API；GPT-SoVITS 保留在 services/gpt-sovits/ 端口 9880；两个引擎互斥运行，通过启动脚本参数切换。