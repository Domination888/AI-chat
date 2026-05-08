# 硬件配置 & 服务部署位置（项目固定约束）

> 本文件是所有规划/编码任务必须遵守的硬件与部署事实。任何 PLAN 或代码方案都应以此为前提。

## 硬件配置

### Mac（开发主机 + 业务后端 + ASR + TTS）
- 芯片：**Apple M4**（ARM 架构，集成 CPU + GPU + ANE）
- 统一内存：**32GB**（CPU/GPU/ANE 共享）
- 操作系统：macOS（darwin 15.5+）
- 加速能力：
  - **Apple Neural Engine (ANE)**：Core ML 框架可调用
  - **Metal GPU**：MPS / MLX / Metal compute
  - **CPU**：ARM NEON + Accelerate 框架（含 AMX 协处理器）
- 关键约束：
  - ❌ 没有 NVIDIA GPU → 不能装 CUDA、不能用 vLLM、不能用 FlashAttention 2
  - ❌ MPS 对部分大模型算子兼容性不全
  - ✅ 跑 whisper.cpp / MLX / Core ML 类项目原生加速，性能强

### Win（GPU 推理盒子，仅跑 LLM）
- 显卡：**NVIDIA RTX 4070 Ti SUPER，16GB 显存**（已被 LLM 吃满）
- 内存：48GB
- 关键约束：
  - ⚠️ **显存已被 Gemma3-27B（或同级 LLM）占满**，不可再叠加任何 GPU 服务
  - ⚠️ Win 仅作为 LLM 推理服务器，**不要往上塞 ASR / TTS / Embedding**

## 服务部署位置（强约束）

| 服务 | 部署位置 | 原因 |
| --- | --- | --- |
| 前端 Vue3 (dev :5173 / nginx :80) | Mac | 业务侧 |
| 后端 Spring Boot (:8080) | Mac | 业务侧 |
| **LLM（LM Studio :1234）** | **Win** | 唯一有 NVIDIA GPU；显存已占满 |
| **ASR** | **Mac** | Win 显存被 LLM 占满；M4 ANE 跑 Whisper 性能足 |
| **TTS（edge-tts :9880）** | Mac | 轻量、本机播放低延迟 |
| MySQL / Redis | Mac | 业务侧 |
| Embedding（RAG 用） | **Mac**（优先 Core ML / MLX）或调 LM Studio embedding 接口 | 同 ASR 理由 |

## 跨机调用拓扑
```
Mac (M4, 32GB)                       Win (4070 Ti S, 16GB 显存吃满)
├─ 前端 Vue3        :5173            └─ LM Studio (Gemma3-27B)  :1234
├─ 后端 SpringBoot  :8080
├─ ASR (本机)       :9000            
├─ TTS (edge-tts)   :9880
├─ MySQL/Redis
└─ Embedding (本机)

唯一跨机调用：
  Mac 后端 ──HTTP SSE──▶ Win LM Studio (192.168.x.x:1234/v1)
```

## 选型铁律（给后续所有规划的硬性输入）
1. **不要在 Win 上加任何 GPU 占用服务**（显存已满）
2. **Mac 端 ASR/TTS/Embedding 必须优先选支持 Apple Silicon 加速（Core ML / MLX / Metal）的方案**
3. **避免大于 ~3B 的模型在 Mac 上做实时推理**，统一内存 32GB 还要留给业务后端 + 前端开发 + 浏览器 + IDE
4. **跑不动就退到云 API**（DashScope / OpenAI 兼容），不要硬塞本地
5. 任何方案都要标注预估资源占用（显存/内存/CPU）和预估延迟