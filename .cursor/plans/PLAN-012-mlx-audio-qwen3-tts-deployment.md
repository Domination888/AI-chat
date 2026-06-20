# PLAN-012: 部署 MLX-Audio + Qwen3-TTS

## 任务概要

在 Mac M4 32GB 上部署基于 MLX 框架的 Qwen3-TTS 服务，利用 Apple Silicon 原生加速（Metal GPU + ANE），替换 GPT-SoVITS 作为主力 TTS 引擎，同时保留 GPT-SoVITS 以便随时切换。

**核心项目**: [Blaizzy/mlx-audio](https://github.com/Blaizzy/mlx-audio) — 基于 Apple MLX 框架的音频处理库，原生支持 Qwen3-TTS 模型推理，MIT 许可。

---

## 为什么选 MLX-Audio + Qwen3-TTS

| 维度 | GPT-SoVITS (现状) | MLX-Audio + Qwen3-TTS |
|---|---|---|
| **推理框架** | PyTorch MPS (兼容性问题多) | Apple MLX (Metal 原生加速) |
| **TTFB** | 1-3 秒 (两阶段架构) | ~200ms (Dual-Track 流式) |
| **语音克隆** | 需 LoRA 微调 | 3 秒零样本克隆 |
| **流式支持** | 需自行 chunk 拼接 | 原生流式输出 |
| **内存占用** | ~3-4GB | ~1.5GB(0.6B) / ~3GB(1.7B 8bit) |
| **中文质量** | 微调后极佳 | SOTA 级别 |
| **指令控制** | 无 | 自然语言控情感/语气/语速 |
| **采样率** | 48000Hz (v2Pro) | 24000Hz |

---

## 硬件资源预估 (Mac M4 32GB)

| 模型变体 | 内存占用 | 预估 TTFB | 推荐场景 |
|---|---|---|---|
| Qwen3-TTS-0.6B-Base bf16 | ~1.5GB | ~200-300ms | 日常主力（推荐先试） |
| Qwen3-TTS-1.7B-Base 8bit | ~2GB | ~300-500ms | 音质优先 |
| Qwen3-TTS-1.7B-VoiceDesign bf16 | ~3.5GB | ~300-500ms | 需要音色设计时 |
| GPT-SoVITS v2Pro (保留) | ~3-4GB | 1-3s | 回退 / 黍模型 |

**关键**: 两个 TTS 服务不会同时运行，切换时停一个启另一个，内存不冲突。

---

## 目录结构

```
services/
├── gpt-sovits/              # 保留，不动
│   ├── GPT-SoVITS/          # 原始仓库
│   └── start.sh             # Mac 启动脚本
└── mlx-audio-tts/           # 新增
    ├── start.sh             # 启动 API 服务
    ├── stop.sh              # 停止服务
    ├── config.yaml          # 服务配置
    ├── requirements.txt     # Python 依赖
    └── README.md            # 使用说明
```

模型权重由 mlx-audio 自动从 HuggingFace 下载到 `~/.cache/huggingface/`，不放入项目目录。

---

## TODO: 环境搭建 ✅

- [x] 创建 `services/mlx-audio-tts/` 目录及基础文件
- [x] 创建 Python 虚拟环境 (Python 3.13)
- [x] 安装依赖: mlx-audio + 服务端依赖
- [ ] 安装 ffmpeg: `brew install ffmpeg` (MP3/FLAC 需要，WAV 不需要)
- [x] 验证 MLX 可用 (mlx 0.31.2)

## TODO: 模型下载与验证 ✅

- [x] 下载 Qwen3-TTS-0.6B-Base bf16
- [x] 基础 TTS 生成验证（Chelsie 音色，24kHz WAV）
- [x] 语音克隆验证（黍参考音频 + ref_text）

## TODO: API 服务封装 ✅

- [x] start.sh / stop.sh / config.yaml / requirements.txt
- [x] API 验证: POST /v1/audio/speech → 200 OK (WAV)
- [x] API 语音克隆验证: ref_audio + ref_text → 200 OK

## TODO: 后端对接 ✅

- [x] VoiceProperties 增加 ttsEngine / mlxAudioBaseUrl / mlxAudioModel / mlxAudioSampleRate
- [x] TtsStrategy 策略接口 + GptSovitsTtsStrategy + MlxAudioTtsStrategy
- [x] VoiceServiceImpl 策略模式重构
- [x] ChatController / ProactiveChatService 动态 sampleRate/format
- [x] application-local.yml MLX-Audio 配置

## TODO: 前端适配 ✅

- [x] 动态采样率（后端下发 sampleRate）
- [x] WAV 格式解码支持（decodeAudioData）
- [ ] MLX-Audio 流式 chunked 响应（暂用整段模式）

## TODO: 启动脚本集成 ✅

- [x] start-tts.sh 支持 --engine 参数
- [x] PID / 日志目录区分

## TODO: 性能基准测试

- [ ] TTFB / 生成速度 / 内存占用基准
- [ ] 中文语音克隆效果对比
- [ ] 流式播放端到端延迟

- [ ] 创建 `services/mlx-audio-tts/` 目录及基础文件
- [ ] 创建 Python 虚拟环境: `uv venv --python 3.12` (在 services/mlx-audio-tts/.venv)
- [ ] 安装依赖: `pip install mlx-audio misaki misaki[zh] soundfile`
- [ ] 安装 ffmpeg: `brew install ffmpeg` (MP3/FLAC 编码需要)
- [ ] 验证 MLX 可用: `python -c "import mlx; print(mlx.__version__)"`

## TODO: 模型下载与验证

- [ ] 下载 Qwen3-TTS-0.6B-Base bf16: 首次运行自动从 HF 下载
  ```
  mlx_audio.tts.generate \
    --model mlx-community/Qwen3-TTS-12Hz-0.6B-Base-bf16 \
    --text '你好，这是测试' \
    --voice Chelsie \
    --play
  ```
- [ ] 下载 Qwen3-TTS-1.7B-Base 8bit (备选):
  ```
  mlx_audio.tts.generate \
    --model mlx-community/Qwen3-TTS-12Hz-1.7B-Base-8bit \
    --text '你好，这是测试' \
    --voice Chelsie \
    --play
  ```
- [ ] 验证中文语音克隆 (用黍的参考音频):
  ```
  mlx_audio.tts.generate \
    --model mlx-community/Qwen3-TTS-12Hz-0.6B-Base-bf16 \
    --text '你好，我是黍' \
    --ref_audio <黍参考音频路径> \
    --play
  ```

## TODO: API 服务封装

- [ ] 编写 `services/mlx-audio-tts/start.sh` 启动脚本
  - 激活虚拟环境
  - 启动 mlx_audio.server: `mlx_audio.server --host 127.0.0.1 --port 9881`
  - 写入 PID 文件到 `unified-logs/pids/`
- [ ] 编写 `services/mlx-audio-tts/stop.sh` 停止脚本
- [ ] 编写 `services/mlx-audio-tts/config.yaml` 默认配置
  ```yaml
  # 默认模型
  model: mlx-community/Qwen3-TTS-12Hz-0.6B-Base-bf16
  # 备选模型
  # model: mlx-community/Qwen3-TTS-12Hz-1.7B-Base-8bit
  host: 127.0.0.1
  port: 9881
  # 默认音色 (Qwen3-TTS 内置)
  default_voice: Chelsie
  # 参考音频路径 (语音克隆用)
  # ref_audio: /path/to/shu_reference.wav
  ```
- [ ] API 兼容性验证:
  - OpenAI 兼容接口: `POST /v1/audio/speech`
  - 请求格式: `{"model": "...", "input": "文本", "voice": "Chelsie"}`
  - 返回: WAV 音频流

## TODO: 后端对接 (Spring Boot)

- [ ] TTS 接口抽象化: 在 `TtsService` 中增加引擎选择逻辑
  - `tts.engine=mlx-audio` 或 `tts.engine=gpt-sovits`
  - 配置项放 `application.yml`
- [ ] MLX-Audio TTS 客户端实现:
  - HTTP 调用 `http://127.0.0.1:9881/v1/audio/speech`
  - 请求体: OpenAI 兼容格式
  - 响应: WAV 二进制 → 转为 PCM 流推送前端
- [ ] 采样率对齐: Qwen3-TTS 输出 24kHz，后端需告知前端 `sampleRate=24000`
  - **重要**: 与 GPT-SoVITS v2Pro 的 48kHz 不同，切换引擎时前端必须同步切换
- [ ] 引擎切换: 运行时通过配置或 API 参数切换，不重启后端

## TODO: 前端适配

- [ ] PCM 播放器适配 24kHz 采样率 (MLX-Audio 模式)
- [ ] 引擎切换时动态更新采样率配置
- [ ] 流式播放: MLX-Audio server 的 chunked 响应直接推前端

## TODO: 启动脚本集成

- [ ] 修改 `startup-scripts/start-tts.sh` 支持参数选择引擎:
  ```bash
  bash start-tts.sh --engine mlx-audio   # 启动 MLX-Audio
  bash start-tts.sh --engine gpt-sovits  # 启动 GPT-SoVITS
  ```
- [ ] PID 文件区分: `unified-logs/pids/tts-mlx-audio.pid` vs `tts-gpt-sovits.pid`
- [ ] 日志目录: `unified-logs/tts/mlx-audio/`

## TODO: 性能基准测试

- [ ] 测量 0.6B bf16 在 M4 上的 TTFB / 生成速度 / 内存占用
- [ ] 测量 1.7B 8bit 在 M4 上的 TTFB / 生成速度 / 内存占用
- [ ] 中文语音克隆效果对比 (黍参考音频 vs GPT-SoVITS 黍 LoRA)
- [ ] 流式播放延迟端到端测试

---

## 部署注意事项

1. **服务端口**: MLX-Audio TTS 服务用 `9881`（GPT-SoVITS 用 `9880`），互不冲突
2. **内存互斥**: 两个 TTS 引擎不同时运行，切换时需先停旧再启新
3. **采样率差异**: Qwen3-TTS=24kHz，GPT-SoVITS v2Pro=48kHz，前端必须动态适配
4. **首次启动**: 模型自动从 HuggingFace 下载（0.6B bf16 ~1.3GB），需网络
5. **ffmpeg**: MLX-Audio 保存 MP3/FLAC 需要，WAV 不需要；`brew install ffmpeg`
6. **Python 版本**: 需要 Python 3.10+，推荐 3.12