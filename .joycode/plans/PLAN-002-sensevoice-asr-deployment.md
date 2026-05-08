# PLAN-002 · SenseVoice ASR 部署（Mac 本机 / 批处理优先）

> 上层目标：[`PLAN-000`](.joycode/plans/PLAN-000-product-overview-neuro-like-ai.md:1)
> 硬件约束：[`.joycode/rules/00-hardware-and-deployment.md`](.joycode/rules/00-hardware-and-deployment.md:1)
> 调用方：[`PLAN-001 阶段 6`](.joycode/plans/PLAN-001-ai-chat-role-play-refactor.md:1)、[`PLAN-004`](.joycode/plans/PLAN-004-neuro-orchestration-interrupt-tools.md:1)

## Task Summary

在 Mac M4 上把 [SenseVoice](https://github.com/FunAudioLLM/SenseVoice) 跑起来，对外提供 OpenAI 风格的 HTTP 批处理接口（整段 wav POST → 文本）。**先跑通再说**：批处理 MVP 满足"按住说话松手 → 1.5s 内拿到文本"即可，流式版后续再升级。

**为什么选 SenseVoice**：
- 中文 / 多语种 / 情感 / 标点 一把梭，开箱比 whisper 中文好
- 模型 ~234M（small），CPU/MPS 都能跑，无需 CUDA
- 开源生态有现成 FastAPI / Triton / runtime 包，启动门槛低
- 支持热词、情感标签输出，对角色扮演场景友好

## 选型与版本（待跑通后再细调）

- 模型：`SenseVoiceSmall`（推荐起步）
- 推理路径：**优先 funasr Python runtime + FastAPI**（生态最成熟）
  - 备选 1：`sensevoice.cpp`（C++ 原生，性能更好但社区更小）
  - 备选 2：MLX 移植（如有）
- 加速：Mac 上能用 MPS 就用 MPS，不行 CPU 也能跑
- 端口：`127.0.0.1:9000`，对外接口 `POST /v1/audio/transcriptions`（OpenAI 兼容形式，方便后端复用）

---

## TODO: 阶段 1 · 环境准备

- [ ] 装 Python 3.10/3.11（建议 conda/mamba 隔离）：`conda create -n sensevoice python=3.10 && conda activate sensevoice`
- [ ] `pip install funasr modelscope torch torchaudio fastapi uvicorn python-multipart`
- [ ] `brew install ffmpeg`（音频格式转换兜底）
- [ ] 工作目录：`mkdir -p ~/services/sensevoice && cd ~/services/sensevoice`

## TODO: 阶段 2 · 离线跑通模型

- [ ] 写最小脚本 `test_offline.py`：
  ```python
  from funasr import AutoModel
  model = AutoModel(
      model="iic/SenseVoiceSmall",
      vad_model="fsmn-vad",  # 可选
      device="mps",          # 不行就 "cpu"
  )
  res = model.generate(input="my_voice.wav", language="auto", use_itn=True)
  print(res)
  ```
- [ ] 准备一段中文 wav（16k mono）测试：`ffmpeg -i my_voice.m4a -ar 16000 -ac 1 -c:a pcm_s16le my_voice.wav`
- [ ] **验收**：能识别出中文，标点正常，时长 5s 处理 < 2s

## TODO: 阶段 3 · 包装成 HTTP 服务（OpenAI 风格批处理）

- [ ] 写 `server.py`（FastAPI），监听 `127.0.0.1:9000`：
  ```
  POST /v1/audio/transcriptions   (multipart: file, language?, prompt?, hotwords?)
    -> { "text": "...", "language": "zh", "emotion": "happy" }
  GET  /healthz
  ```
- [ ] 内部流程：接收文件 → 必要时 ffmpeg 转 16k mono wav → `model.generate(...)` → 抽取文本 + 情感 + 语种
- [ ] 启动：`uvicorn server:app --host 127.0.0.1 --port 9000 --workers 1`（一个 worker 即可，模型独占）
- [ ] 冒烟：
  ```bash
  curl -F "file=@my_voice.wav" http://127.0.0.1:9000/v1/audio/transcriptions
  ```
- [ ] **验收**：5s 中文短句端到端 < 2s 拿到文本

## TODO: 阶段 4 · launchd 守护（开机自启）

- [ ] 创建 `~/Library/LaunchAgents/com.aichat.sensevoice.plist`，把 `~/services/sensevoice` 下的 `python server.py` 包进去（路径写绝对值）
- [ ] `launchctl load ~/Library/LaunchAgents/com.aichat.sensevoice.plist`
- [ ] **验收**：重启 Mac 后 `curl http://127.0.0.1:9000/healthz` 仍可用，日志写到 `/tmp/sensevoice.{out,err}.log`

## TODO: 阶段 5 · 后端对接联调（与 PLAN-001 阶段 6 配合）

- [ ] 后端 `application.yml` 加 ASR 配置（见 PLAN-001 阶段 6）
- [ ] `SenseVoiceClient`（OkHttp / WebClient）：multipart POST → 反序列化为 `AsrResult{text, language, emotion}`
- [ ] 把 `roleCard.hotwords` 通过 `prompt` 或 `hotwords` 字段透传
- [ ] **验收**：后端 `/api/audio/asr` 接收前端 webm，返回正确文本

---

## 资源占用预估

| 项 | 估值 |
| --- | --- |
| 内存 | 1.5~2.5 GB |
| 启动耗时 | 5~15s |
| 5s 音频处理 | 0.5~1.5s（MPS）/ 1~2s（CPU） |

## 验收标准（整体）

- [ ] 5s 中文短句端到端 < 2s
- [ ] 长句（30s）能正确识别，不丢段
- [ ] 情感 / 语种字段可正常返回（用于后续 Live2D / 路由）
- [ ] launchd 重启后服务自动恢复

## 风险与回退

1. funasr 在 Mac MPS 上某些算子不兼容 → 退到 CPU，性能仍可用（5s 音频 < 2s）
2. 内存吃紧（同时跑业务后端 + GPT-SoVITS） → 改用更小的模型或考虑 `sensevoice.cpp` C++ 实现
3. 中文方言或专业术语识别差 → 用 `hotwords` 注入角色名 / 世界观词
4. 全部不行 → 临时切阿里云 DashScope `paraformer-realtime-v2` 兜底（API key 模式）

## 后续升级路径（不在本 PLAN）

- 流式 ASR：funasr 也有 `paraformer-streaming` / sensevoice 流式分支，PLAN-004 再切
- 服务端 VAD 切句：当前由前端做，将来可挪到后端