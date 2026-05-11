# PLAN-006 · LLM 上下文长度策略与 Gemma4 部署

> 关联：[`00-hardware-and-deployment.md`](.joycode/rules/00-hardware-and-deployment.md:1)
> 调用方：[`PLAN-001`](.joycode/plans/PLAN-001-ai-chat-role-play-refactor.md:1)、[`PLAN-004`](.joycode/plans/PLAN-004-neuro-orchestration-interrupt-tools.md:1)
> 目标：把"Win LM Studio 上 Gemma4-31B 应该开多大 ctx_len"这件事彻底定下来，避免每次部署都拍脑袋。

---

## 背景

- 模型：**Gemma4-31B Dense**（Google 2026-04-02 发布，Apache 2.0）
- 部署：Win RTX 4070 Ti S 16GB 显存，LM Studio 加载 GGUF Q4_K_M / Q5_K_M
- 模型上限：**256K 上下文**（架构能塞下，但不代表都该塞）
- 当前部署：ctx_len=12K（**不合理，需要降到 8K**）
- 调用方式：API 调用（OpenAI 兼容 `/v1/chat/completions`），**后端 PromptAssembler 自管上下文**

---

## 核心决定

### 1. ctx_len 设为 **8192**（不是 12K，更不是 256K）

**理由：**

#### 1.1 后端在自管上下文，模型侧不需要长 ctx

[`PromptAssembler`](src/main/java/org/example/aichat/service/impl/ChatServiceImpl.java:1) 已经在每次请求前主动裁剪：

| 部分 | 估算 token |
| --- | --- |
| 系统人设 + 角色卡 | ~1500 |
| 历史滑窗（10 轮） | ~3000 |
| RAG 检索 Top-3 片段 | ~1500 |
| 用户当前消息 | ~500-1000 |
| 工具 schema（开 tools 时） | ~500-800 |
| **合计** | **~7000-7800** |

发送给 LLM 的 prompt 已经控制在 8K 以内，**模型侧的 ctx_len 只要不小于这个值就行**。

#### 1.2 ctx_len 越大，KV cache 显存越大（关键）

Gemma4-31B Q4_K_M 单 token 的 KV cache 约 **0.18 MB**：

| ctx_len | KV cache 显存 | Gemma4-31B Q4 总显存（权重 ~17GB + KV） |
| --- | --- | --- |
| 4K | ~720 MB | **快爆**（4070 Ti S 只有 16GB） |
| 8K | ~1.4 GB | **刚好能稳定吃下** ✅ |
| 12K | ~2.2 GB | 显存压力大，可能 OOM 或换出 |
| 16K | ~2.9 GB | 几乎肯定 OOM |
| 32K | ~5.8 GB | 不可能 |
| 256K | ~46 GB | 笑话 |

> 注：Q5_K_M 权重约 21GB，4070 Ti S 16GB **跑不了 Q5 的 31B**，必须 Q4。
> 实际 LM Studio 还会把部分层 offload 到内存，但 KV cache 必须在显存里。

#### 1.3 ctx_len 越大，prefill（首 token）延迟越高

- prefill 时间 ≈ O(prompt_tokens × ctx_len_factor)
- 即便 prompt 只有 2K，把 ctx_len 设成 256K 也会让 attention 矩阵开销变大
- ctx_len = 8K 比 12K 的首 token 延迟约低 **15-25%**

#### 1.4 256K 是模型架构上限不是建议值

Gemma4 文档里的 256K 是"能塞进去且模型不崩"，不是"塞进去性能不掉"。所有 long-context 模型在 ctx_len 接近上限时都会出现：
- 中段遗忘（lost in the middle）
- 速度断崖
- 对硬件要求拉满

**对于 API 调用 + 后端自管上下文的本项目，长 ctx 是负资产。**

### 2. 后端硬上限：拼装后总 token 超过 7000 立即触发滑窗摘要

**为什么**：留 1K 缓冲给 LLM 输出，避免请求时 input + output > ctx_len 被截断。

**实现位置**：[`ChatServiceImpl`](src/main/java/org/example/aichat/service/impl/ChatServiceImpl.java:1) prompt 装配最后一步加：

```java
int totalTokens = estimateTokens(allMessages);
if (totalTokens > 7000) {
    // 触发滑窗摘要：把最早的 N 轮压成 200 字摘要塞回去
    summarizeOldestTurns(allMessages);
}
```

token 估算可以用 `OpenAiTokenCountEstimator` 或简单的 `chars / 1.5`（中文）。

### 3. KV cache 优化策略（Win LM Studio 配置）

LM Studio 模型加载界面（Gemma4-31B GGUF）：

| 参数 | 推荐值 | 说明 |
| --- | --- | --- |
| `n_ctx` / `context_length` | **8192** | 不要拉到 12K/256K |
| `n_batch` | 512 | 默认即可 |
| `flash_attn` | ON | 4070 Ti S 支持，省显存 |
| `cache_type_k` | `q8_0` | KV 量化到 8bit，进一步省 ~30% KV 显存 |
| `cache_type_v` | `q8_0` | 同上 |
| `n_gpu_layers` | 全卸载（-1） | 16GB 显存装下整个 Q4，不要 split |
| `kv_offload` | ON | 必须 |

启用 KV 量化后，8K KV cache 从 1.4GB → ~1.0GB，留更多空间给 prefill。

---

## 操作清单

### Win 侧
- [ ] LM Studio 加载 `gemma-4-31b-it-Q4_K_M.gguf`
- [ ] `n_ctx = 8192`，开 flash_attn，KV cache 量化 q8_0
- [ ] 启动 server，验证 `:1234/v1/models` 返回 `gemma-4-31b-it`
- [ ] 显存占用确认：`nvidia-smi` 显示 14~15.5 GB（留 0.5~1.5GB buffer）

### Mac 后端侧
- [ ] [`application-local.yml`](src/main/resources/application-local.yml:42) 已更新到 Gemma4-31B（已完成）
- [ ] `ChatServiceImpl` 加 prompt token 估算 + 7000 硬上限
- [ ] 滑窗摘要任务（>10 轮触发，压成 200 字塞回 system prompt 后）

### 验收
- [ ] 单轮对话首 token < 800ms（局域网内）
- [ ] 30 轮持续对话不 OOM、不 ctx 截断
- [ ] `nvidia-smi` 显存稳定在 16GB 以内不爆

---

## 常见误区

| 误区 | 真相 |
| --- | --- |
| "Gemma4 支持 256K，那就开 256K 吧" | KV cache 直接把显存撑爆，且 prefill 慢到没法用 |
| "ctx_len 越大模型越聪明" | 中段遗忘问题更严重，反而劣化 |
| "API 调用方应该拉满 ctx 让用户随便发" | 后端 PromptAssembler 才是"应该限制 prompt 长度"的地方 |
| "12K 比 8K 多塞历史" | 后端滑窗本来就只发 ~3K 历史，多余 4K 全是空 KV，纯浪费显存 |

---

## 风险与回退

1. 如果以后模型升级到更大量化（Q5_K_M 或 BF16）→ ctx_len 还要进一步压到 4K 才能塞下
2. 如果以后用户场景需要长文档分析 → 单独建一个 `/v1/longcontext` 端点，临时加载 26B-MoE（更小激活，KV 占用低）顶上
3. 如果 KV cache 量化导致质量下降明显 → 回退到 cache_type=f16，配套把 ctx_len 进一步压到 6K