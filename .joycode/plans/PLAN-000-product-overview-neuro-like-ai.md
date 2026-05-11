# PLAN-000 · 产品总纲：类 Neuro-sama 的 AI 主播 / 陪伴

> 这是所有 PLAN 的根。具体技术细节后面单独细聊，本文件只锁**目标 / 范围 / 模块边界 / PLAN 关系**。
> 硬件与部署位置：[`.joycode/rules/00-hardware-and-deployment.md`](.joycode/rules/00-hardware-and-deployment.md:1)

## 一句话目标

做一个像 **Neuro-sama** 一样能听、能想、能说、能做、能被打断的 AI —— 选定角色卡后，用户开口说话或发弹幕，AI 用专属音色实时回话，配合 Live2D 表情/动作，必要时调用工具完成任务。

## 核心特性（最终态）

1. **听**：麦克风实时采音 → VAD 切句 → SenseVoice ASR（先批处理跑通）→ 文本
2. **想**：Spring Boot 后端拼装 prompt（人设 + 滑窗 + RAG 记忆 + 工具）→ Win LM Studio（Gemma4-31B Dense，ctx_len=8K）流式生成
3. **说**：LLM token 流按句切 → GPT-SoVITS **流式 TTS**（边合成边推前端）→ 浏览器边收边播
4. **做**：LLM 可调用 Tools（MCP 协议或自定义函数），如查天气、控制 Live2D 动作、查 RAG、写日记
5. **打断**：用户说话/发弹幕时，立即 cancel 当前 LLM + 清 TTS 队列 + 停播放，200ms 内静音
6. **演**：Live2D 模型表情/动作/嘴型与语音同步
7. **多源输入**：语音、弹幕、定时自言自语 共存，按优先级合流

## 模块边界（一图看清）

```
┌─────────── Mac (M4 32GB) ────────────┐        ┌─ Win (4070 Ti S 16G 满) ─┐
│                                      │        │                          │
│  Vue3 前端 :5173/:80                  │        │  LM Studio :1234         │
│   ├─ 麦克风采音 + VAD                  │        │  (Gemma4-31B, OpenAI 兼容) │
│   ├─ Live2D 渲染 + 表情/嘴型           │        │                          │
│   ├─ TTS 音频流播放（保序+可打断）       │        └──────────▲───────────────┘
│   └─ 弹幕/UI                          │                   │ SSE
│            │ WebSocket                │                   │
│            ▼                          │                   │
│  Spring Boot 后端 :8080 ─────────────────HTTP SSE─────────┘
│   ├─ ConversationOrchestrator (输入路由 / 打断 / 状态机)
│   ├─ PromptAssembler (人设+滑窗+RAG+工具)
│   ├─ ToolRegistry / Tool Executor (函数调用)
│   ├─ SentenceSplitter (LLM token → 句)
│   ├─ MemoryService (Redis 滑窗 + MySQL 长期 + 摘要)
│   └─ RagService (LangChain4j EmbeddingStore)
│                                      │
│  SenseVoice ASR :9000 (本机)          │
│  GPT-SoVITS  :9880 (本机, 流式 mp3)   │
│  MySQL :3306 / Redis :6379            │
└──────────────────────────────────────┘
```

## PLAN 关系（计划文件目录）

| 文件 | 作用 | 状态 |
| --- | --- | --- |
| [`PLAN-000`](.joycode/plans/PLAN-000-product-overview-neuro-like-ai.md:1) | **本文**：产品目标、模块、PLAN 索引 | 现行 |
| [`PLAN-001`](.joycode/plans/PLAN-001-ai-chat-role-play-refactor.md:1) | 项目骨架：前后端搭建、角色卡、对话、记忆、RAG、UI、部署 | 现行（修订） |
| [`PLAN-002`](.joycode/plans/PLAN-002-sensevoice-asr-deployment.md:1) | **SenseVoice ASR** 批处理部署 + HTTP 接入（先跑通） | 现行 |
| [`PLAN-003`](.joycode/plans/PLAN-003-gpt-sovits-streaming-tts.md:1) | **GPT-SoVITS 流式 TTS** 部署 + 边合成边推 + 前端边收边播 | 现行 |
| [`PLAN-004`](.joycode/plans/PLAN-004-neuro-orchestration-interrupt-tools.md:1) | **类 Neuro 核心**：会话编排、打断、Tools 函数调用、Live2D 联动、多源输入 | 现行 |
| ~~`PLAN-002-qwen3-asr-deployment.md`~~ | 已废弃（Win 显存不够），墓碑保留可直接 rm | 废弃 |

## 总体里程碑

| 里程碑 | 含义 | 主要 PLAN |
| --- | --- | --- |
| **M1 文字闭环** | 选角色 → 文字输入 → 流式回复 + 记忆 + RAG | PLAN-001 阶段 1~5 |
| **M2 单轮语音闭环** | 录音 → SenseVoice 批处理识别 → LLM → GPT-SoVITS 流式 TTS → 浏览器边收边播 | PLAN-002 + PLAN-003 |
| **M3 类 Neuro 实时主播** | VAD 实时切句、打断、Tools、Live2D 联动、弹幕 + 自言自语 | PLAN-004 |

## 选型决定（所有 PLAN 必须遵守）

- **LLM**：Win LM Studio（Gemma4-31B Dense，ctx_len=8K），OpenAI 兼容 SSE，**唯一跨机调用**
- **ASR**：**SenseVoice**（Mac 本机），先批处理（整段 wav POST）→ 后续可换流式实现
- **TTS**：**GPT-SoVITS**（Mac 本机），**必须流式**（chunk mp3/wav 实时往前端推）
- **Embedding**：**Mac 本机常驻**（MLX bge-small-zh / bge-m3 或 Core ML）；**绝对不要放 Win**（显存已被 Gemma4 占满）；兜底走 DashScope / OpenAI 兼容 API
- **MQ/缓存**：Redis（滑窗、状态、被打断标记）
- **持久化**：MySQL（角色卡、会话、消息、长期记忆、日记）
- **Tools**：先内置函数注册（天气、Live2D 动作、RAG 查询、记日记），后续可对接 MCP

## 当前阶段建议（路线推进顺序）

1. 把 [`PLAN-002`](.joycode/plans/PLAN-002-sensevoice-asr-deployment.md:1) 的 SenseVoice 批处理跑起来（最小验证）
2. 把 [`PLAN-003`](.joycode/plans/PLAN-003-gpt-sovits-streaming-tts.md:1) 的 GPT-SoVITS 流式 TTS 跑起来
3. 用 [`PLAN-001`](.joycode/plans/PLAN-001-ai-chat-role-play-refactor.md:1) 的阶段 1~7 把"录音→识别→LLM→流式 TTS→播放"非实时版本拉通（M2）
4. 进入 [`PLAN-004`](.joycode/plans/PLAN-004-neuro-orchestration-interrupt-tools.md:1) 升级为实时打断 + Tools + Live2D（M3）

## 待后续细聊的技术点（占位，本文件不展开）

- VAD 引擎选型（Silero VAD vs WebRTC VAD）
- 流式 ASR（边说边出字）的升级路径
- 工具调用：内置函数 vs MCP
- 上下文压缩策略（每 N 轮总结 vs 触发式）
- Live2D 表情情感来源（LLM 标签 vs 本地分类器）
- 直播弹幕接入（B 站 WS）
- 离线 / 在线 Embedding 切换