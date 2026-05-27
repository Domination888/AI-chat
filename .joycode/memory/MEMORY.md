- [TTS 调用必须对齐 GPT-SoVITS WebUI 的实际推理参数](feedback_tts_params.md) — 调 GPT-SoVITS api_v2 /tts 时，必须严格按 webui 1C-推理 页面的设置传参，否则又慢又不像

- [黍模型用 GPT-SoVITS v4 训练但需要按 v2Pro 推理](project_shu_model_version.md) — 黍 LoRA 权重虽然是 v4 版本训练产出，但推理时 tts_infer.yaml 必须用 v2Pro 加载，且 webui 也是用 v2Pro 推理

- [GPT-SoVITS raw PCM 输出采样率按版本不同，v2Pro/v3/v4 是 48000](feedback_tts_sample_rate.md) — 后端把 raw PCM 流给前端时必须按推理版本告知正确 sampleRate，否则前端按错误采样率拼 AudioBuffer 会变速变调

- [mlx-audio-qwen3-tts-sample-rate](feedback_mlx_audio_sample_rate.md) — MLX-Audio + Qwen3-TTS 输出采样率 24kHz，与 GPT-SoVITS v2Pro 的 48kHz 不同，切换引擎时前端必须动态适配

- [mlx-audio-tts-deployment-plan](project_mlx_audio_tts_plan.md) — 已制定 MLX-Audio + Qwen3-TTS 部署方案 (PLAN-012)，部署到 services/mlx-audio-tts/，保留 GPT-SoVITS 可切换
