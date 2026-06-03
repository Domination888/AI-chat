# 硬件配置 & 服务部署位置（项目固定约束）

> 本文件是所有规划/编码任务必须遵守的硬件与部署事实。任何 PLAN 或代码方案都应以此为前提。
> 最近一次修订：2026-05（TTS 迁移到 Win Astra/Genie-TTS）

## 硬件配置

### Mac（开发主机 + 业务后端 + ASR + Embedding）
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

### Win（GPU 推理盒子 + TTS，跑 LLM + Astra TTS）
- 显卡：**NVIDIA RTX 4070 Ti SUPER，16GB 显存**（已被 LLM 吃满）
- 内存：48GB
- LLM：**Gemma4-31B Dense（量化版，Apache 2.0）**
  - 谷歌 2026-04-02 发布的 Gemma 4 家族旗舰 Dense 模型
  - 原生 **256K 上下文窗口**（远超本项目实际需要）
  - 4070 Ti S 16GB 跑量化版（GGUF Q4_K_M / Q5_K_M）已把显存吃满
- 关键约束：
  - ⚠️ **显存已被 Gemma4-31B 占满**，不可再叠加任何 GPU 服务（包括 Embedding、ASR、Reranker）
  - ⚠️ Win 仅作为 LLM 推理 + TTS 服务，**不要往上塞 ASR / Embedding**
  - ⚠️ LM Studio 部署时 **context length 建议设 8K，而不是 12K/256K**（理由见下）

## 服务部署位置（强约束）

| 服务 | 部署位置 | 原因 |
| --- | --- | --- |
| 前端 Vue3 (dev :5173 / nginx :80) | Mac | 业务侧 |
| 后端 Spring Boot (:8080) | Mac | 业务侧 |
| **LLM（LM Studio :1234，Gemma4-31B）** | **Win** | 唯一有 NVIDIA GPU；显存已占满 |
| **ASR** | **Mac** | Win 显存被 LLM 占满；M4 ANE 跑 Whisper 性能足 |
| **TTS（Astra/Genie-TTS :5000）** | **Win** | TTS 已迁移到 Win；Astra CPU 推理，不占 GPU 显存 | 轻量、本机播放低延迟 |
| MySQL / Redis | Mac | 业务侧 |
| **Embedding（RAG 用，常驻服务）** | **Mac** | Win 显存被 LLM 占满；后端每次 query 都要算 embedding，不能临时加载 |

## Embedding 部署原则（重要）

**为什么 Embedding 必须放 Mac 且必须常驻**：
1. RAG 不只在"建库"用 embedding，**每次用户提问都要对 query 做向量化**，是热路径
2. 临时加载/卸载会与 Gemma4 抢 Win 显存，导致 LLM 推理抖动（双向换页）
3. Mac M4 跑 bge-small / bge-m3 走 MLX / Core ML 极轻量（<1GB 内存，<50ms 延迟）

**推荐方案**（按优先级）：
- 主：**bge-small-zh-v1.5**（Mac 本机 MLX，~100MB 内存，离线 query embedding）
- 备：**bge-m3**（多语言 + 长文本，~2GB，建库时用）
- 兜底：DashScope / OpenAI 兼容 embedding API

## 跨机调用拓扑
```
Mac (M4, 32GB)                       Win (4070 Ti S, 16GB 显存吃满)
├─ 前端 Vue3        :5173            └─ LM Studio (Gemma4-31B Q4/Q5) :1234
├─ 后端 SpringBoot  :8080                  ctx_len=8K（项目实际需求 << 256K）
├─ ASR (本机)       :9000             ├─ Astra TTS (Genie-TTS, CPU) :5000
├─ Embedding (本机) :*                     samplingRate=32000Hz
├─ MySQL/Redis                           └─ 音色：chenxing, Shu_v2proplus
└─ ...

跨机调用：
  Mac 后端 ──HTTP SSE──▶ Win LM Studio (192.168.x.x:1234/v1)
  Mac 后端 ──HTTP GET───▶ Win Astra TTS  (192.168.x.x:5000/api/tts/predict-stream)
```

## LLM 上下文长度策略（Gemma4 部署侧）

**结论：Win 上 LM Studio 把 ctx_len 设为 8K（不是 12K，更不是 256K）**

理由：
1. **后端自己管理上下文**：`PromptAssembler` 已经按"系统提示 + 滑窗 + RAG 片段 + 工具 schema"主动裁剪，发到 LLM 的实际 token 数量上限可控（系统 ~1.5K + 滑窗 ~3K + RAG ~1.5K + 用户 ~1K ≈ 7K）
2. **ctx_len 越大，KV cache 显存占用越大**：Gemma4-31B Q4 单 token KV ≈ 0.18MB，8K → 1.4GB，16K → 2.9GB，32K → 5.8GB；显存已经吃满，留不出大 KV
3. **ctx_len 越大，prefill 延迟越高**：首 token 延迟与提示长度近似线性，长 ctx 让短提示也变慢
4. **256K 是模型上限不是建议值**：Gemma4 文档里的 256K 是"能塞进去"，不是"塞进去性能不掉"

**操作建议**：
- LM Studio → 模型加载参数 → `n_ctx` / `context_length` = **8192**
- 配套关闭 KV cache 全保留（用 sliding window / quantized KV 进一步省显存）
- 后端 `PromptAssembler` 加硬上限：拼装后总 token 超过 7000 立即触发滑窗摘要

## 选型铁律（给后续所有规划的硬性输入）
1. **不要在 Win 上加任何 GPU 占用服务**（显存已满）
2. **Mac 端 ASR/Embedding 必须优先选支持 Apple Silicon 加速（Core ML / MLX / Metal）的方案**（TTS 已迁到 Win，Mac 不跑 TTS）
3. **避免大于 ~3B 的模型在 Mac 上做实时推理**，统一内存 32GB 还要留给业务后端 + 前端开发 + 浏览器 + IDE
4. **跑不动就退到云 API**（DashScope / OpenAI 兼容），不要硬塞本地
5. **API 调用方式下不要追求大上下文**：上下文由后端管理，Win LM Studio ctx_len = 8K
6. 任何方案都要标注预估资源占用（显存/内存/CPU）和预估延迟