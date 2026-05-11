---
name: TTS 调用必须对齐 GPT-SoVITS WebUI 的实际推理参数
description: 调 GPT-SoVITS api_v2 /tts 时，必须严格按 webui 1C-推理 页面的设置传参，否则又慢又不像
type: feedback
---

GPT-SoVITS api_v2 的 /tts 调用必须复刻 webui 的"1C-推理"页设置，任何省略 = 走 api_v2 的默认值，会导致音色漂、推理慢。

**Why:** 用户在 webui 用同一套权重（v2Pro: 黍-e10.ckpt + 黍_e10_s190_l32.pth）效果又快又像，但后端调用又慢又不像 —— 唯一差别是后端漏传/传错了几个关键参数（最致命：sample_steps 漏传 → 走默认 32，webui 用 8，慢 4 倍且采样不稳）。

**How to apply:**
- v2Pro/v3/v4 模型必须显式传 `sample_steps`，对齐 webui 截图（默认 webui 是 8）
- `text_split_method`：webui "凑四句一切" = `cut1`，不是 `cut5`（cut5 是按标点切）
- `top_k=15, top_p=1.0, temperature=1.0, speed_factor=1.0, fragment_interval=0.3` —— 不要擅自调小 temperature 或加速 speed_factor，会改变音色
- 改完任何 TTS 参数后，对照 webui 同步生成同一句话比较音色，再确认