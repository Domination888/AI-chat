# PLAN-003 · GPT-SoVITS 流式 TTS 部署（Mac 本机 / 边合成边推）

> 上层目标：[`PLAN-000`](.joycode/plans/PLAN-000-product-overview-neuro-like-ai.md:1)
> 硬件约束：[`.joycode/rules/00-hardware-and-deployment.md`](.joycode/rules/00-hardware-and-deployment.md:1)
> 调用方：[`PLAN-001 阶段 7`](.joycode/plans/PLAN-001-ai-chat-role-play-refactor.md:1)、[`PLAN-004`](.joycode/plans/PLAN-004-neuro-orchestration-interrupt-tools.md:1)

## Task Summary

在 Mac M4 上部署 [GPT-SoVITS](https://github.com/RVC-Boss/GPT-SoVITS)，对外提供**流式合成 HTTP 接口**：后端把一句话发过去，TTS 立刻一边合成一边把音频 chunk 通过 chunked transfer / SSE 推回来。后端把 chunk 透传给前端，前端用 `MediaSource` 边收边播。**目标：从"开始合成一句"到"听到第一个音"延迟 < 800ms**。

**为什么选 GPT-SoVITS**：
- 角色音色克隆能力强（少量参考音频即可），契合"角色扮演 / AI 主播"
- 支持流式输出（社区已有多个 streaming 分支 / fork）
- Mac M 系列可跑（不依赖 CUDA），MPS / CPU 都能用

**关键技术点**：
- 服务端：`/tts?text=...&voice_id=...&stream=true` 返回 `audio/mpeg` chunked 流（或 wav PCM 流）
- 后端：保持长连接 chunked 透传，**不缓冲整段**
- 前端：`MediaSource + SourceBuffer` 接 mp3 chunk，第一帧就 `audio.play()`

## 选型与版本

- 项目：GPT-SoVITS 主仓库或社区 streaming 分支（具体哪个 fork 待技术细节阶段确定）
- 输出格式：**优先 mp3 chunk**（前端 MSE 兼容性最好）；不行用 wav PCM + 自实现 PCM player
- 端口：`127.0.0.1:9880`
- 推理：MPS 优先，不行 CPU

---

## TODO: 阶段 1 · 环境准备

- [ ] Conda 环境隔离：`conda create -n sovits python=3.10 && conda activate sovits`
- [ ] Clone GPT-SoVITS 仓库到 `~/services/gpt-sovits`
- [ ] 装依赖（按官方 README）：`pip install -r requirements.txt`
- [ ] 确认 ffmpeg：`brew install ffmpeg`
- [ ] 准备角色参考音频：每个角色 1 段 5~10s 干声 + 对应文本（写进 `role_card.voice_id` 关联的目录）

## TODO: 阶段 2 · 离线推理跑通（非流式先过一遍）

- [ ] 用 GPT-SoVITS 自带 webui 或 CLI 跑一次合成，输入参考音频 + 文本，确认输出 wav 正常
- [ ] device 设 `mps`，不行换 `cpu`（CPU 会慢但能用，MVP 阶段允许）
- [ ] **验收**：合成 20 字中文，整段时长可接受；音色像参考人

## TODO: 阶段 3 · 启动流式 HTTP 服务

- [ ] 用 GPT-SoVITS 的 `api.py` / `api_v2.py` 或社区 streaming fork（具体方案在动手时定）
- [ ] 接口约定（最低集合）：
  ```
  POST /tts
  body: {
    "text": "你好世界",
    "ref_audio": "/path/to/ref.wav",
    "ref_text": "参考文本",
    "language": "zh",
    "stream": true,
    "format": "mp3"
  }
  -> 200 OK, Transfer-Encoding: chunked, Content-Type: audio/mpeg
     <音频 chunk 1>
     <音频 chunk 2>
     ...
  ```
- [ ] 启动监听 `127.0.0.1:9880`
- [ ] 冒烟（curl 看 chunk 是不是流式而不是攒满才回）：
  ```bash
  curl -N -X POST http://127.0.0.1:9880/tts -H "Content-Type: application/json" \
    -d '{"text":"你好，今天天气真好","ref_audio":"...","ref_text":"...","stream":true,"format":"mp3"}' \
    --output stream.mp3
  ```
  **观察点**：开始下载到第一个字节的时间 < 800ms
- [ ] **验收**：边下边能播（用 `mpv -` 测）

## TODO: 阶段 4 · launchd 守护

- [ ] `~/Library/LaunchAgents/com.aichat.sovits.plist`（路径绝对，env 带 PATH/PYTHON）
- [ ] `launchctl load ...`
- [ ] **验收**：重启 Mac 后 `curl http://127.0.0.1:9880/healthz` 可用

## TODO: 阶段 5 · 后端流式转发（Spring Boot）

- [ ] `TtsClient` 接口：`Flux<DataBuffer> stream(String text, VoiceProfile voice)`（Reactor）
- [ ] 实现 `GptSovitsStreamingClient`：用 WebClient `.exchangeToFlux(...)` 拿到原始字节流，直接 emit DataBuffer
- [ ] **不要在后端做整段缓冲**，否则流式优势就没了
- [ ] 控制器层：`/api/tts/stream` 用 `ResponseEntity<Flux<DataBuffer>>`，`Content-Type: audio/mpeg`，禁缓存
- [ ] 角色音色：`role_card.voice_id` → `VoiceProfile{ref_audio, ref_text, language}`，从配置 / 数据库读
- [ ] **验收**：`curl -N` 拉后端 `/api/tts/stream` 也是边下边出声

## TODO: 阶段 6 · 前端 MediaSource 边收边播

- [ ] 封装 `StreamingMp3Player`：
  - `new MediaSource()` → `audio.src = URL.createObjectURL(ms)`
  - `sourceopen` 后 `addSourceBuffer('audio/mpeg')`
  - fetch 后端 `/api/tts/stream`（POST/GET），用 `response.body.getReader()` 不断读取并 `appendBuffer`
  - 第一次 `appendBuffer` 完成后 `audio.play()`，不要等整段
  - 提供 `stop()`：`abort()` reader、`removeSourceBuffer`、`audio.pause()`、`audio.src = ''`
- [ ] 提供测试页：输入框 + 播放按钮 + 停止按钮，单独验证流式播放
- [ ] **验收**：点击播放 → 1 秒内听到第一个字 → 播放过程中点停止 < 200ms 静音

---

## 资源占用预估

| 项 | 估值 |
| --- | --- |
| 内存 | 2~4 GB |
| 启动耗时 | 10~30s |
| 首字节延迟（短句） | 400~800 ms（MPS） |
| 长句吞吐 | 实时率 RTF < 0.6（一边合成一边播得过来） |

## 验收标准（整体）

- [ ] curl 抓流：从发请求到第一个字节 < 800ms
- [ ] 前端边收边播，第一个音感知 < 1s
- [ ] 中途调 `stop()` → 200ms 内静音
- [ ] 同 Mac 上和 SenseVoice + 业务后端共存，不 OOM

## 风险与回退

1. GPT-SoVITS 主仓 streaming 不稳 / 不存在 → 用社区 fork（动手前再选定）
2. MPS 兼容性问题 → 退 CPU（合成速度会下降，长句可能跟不上播放）
3. mp3 流式被 MediaSource 兼容性坑 → 切 wav PCM + 自写 AudioWorklet 播放器
4. 实时性达不到 → 先用 edge-tts（之前已验证可流式 mp3）顶上，待优化

## 后续升级（不在本 PLAN）

- 句级流水线编排（LLM token → 切句 → 多句并发 TTS）：在 PLAN-004
- 音色训练 / 微调流程：单独 PLAN
- 嘴型同步（音频 RMS → Live2D 嘴部参数）：在 PLAN-004