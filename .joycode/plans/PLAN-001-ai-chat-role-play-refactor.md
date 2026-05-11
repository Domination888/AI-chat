# PLAN-001 · 项目骨架（前后端 + 角色 + 对话 + 记忆 + RAG + 单轮语音闭环）

> 上层目标：[`PLAN-000`](.joycode/plans/PLAN-000-product-overview-neuro-like-ai.md:1)
> 硬件与部署位置：[`.joycode/rules/00-hardware-and-deployment.md`](.joycode/rules/00-hardware-and-deployment.md:1)
> ASR 部署细节：[`PLAN-002`](.joycode/plans/PLAN-002-sensevoice-asr-deployment.md:1)
> TTS 部署细节：[`PLAN-003`](.joycode/plans/PLAN-003-gpt-sovits-streaming-tts.md:1)
> 实时打断 / Tools / Live2D：[`PLAN-004`](.joycode/plans/PLAN-004-neuro-orchestration-interrupt-tools.md:1)

## Task Summary

完成 M1（文字闭环）和 M2（单轮语音闭环）：搭好前后端、角色卡、流式对话、记忆、RAG，最后把"按住说话→SenseVoice 批处理→LLM→GPT-SoVITS 流式 TTS→边收边播"打通。**不在本 PLAN 处理**：实时 VAD 切句、barge-in 打断、Tools、Live2D（这些都在 PLAN-004）。

## 技术选型（已定稿，由 PLAN-000 锁定）

- 后端：Spring Boot 3 + LangChain4j（OpenAI starter 指向 Win LM Studio）+ MyBatis + MySQL + Redis
- 前端：Vue3 + Vite + Pinia + Element Plus + Tailwind
- LLM：Win LM Studio（Gemma4-31B Dense，ctx_len=8K），OpenAI 兼容 `:1234/v1`
- **ASR：SenseVoice（Mac 本机批处理）**，HTTP POST 整段 wav 拿文本
- **TTS：GPT-SoVITS（Mac 本机流式）**，后端 chunk 转发，前端 MSE 边收边播
- 记忆：Redis 滑窗 + MySQL 长期 + 摘要任务
- RAG：LangChain4j EmbeddingStore（内存版起步），Embedding **常驻 Mac 本机**（MLX bge-small-zh / bge-m3），不依赖 Win

## 部署架构（参考 PLAN-000 模块图）

仅 LLM 跨机调用，其余全部本机（127.0.0.1）。

---

## TODO: 阶段 1 · 项目骨架
- [ ] 清理遗留代码，保留 [`pom.xml`](pom.xml:1)、[`init.sql`](init.sql:1) 作参考
- [ ] 后端包结构：`controller / service / repository / domain / infra(llm,asr,tts,rag,memory) / config / dto`
- [ ] 后端 `application.yml`（dev/local/prod），LLM `base-url=http://<win-ip>:1234/v1`
- [ ] Win：LM Studio 加载 **Gemma4-31B Dense（GGUF Q4_K_M / Q5_K_M）**，**ctx_len=8192（不要拉到 256K）**，开 `Serve on Local Network`，防火墙放行 1234，设静态 IP / 关休眠
- [ ] Mac：`curl http://<win-ip>:1234/v1/models` 验证跨机可达
- [ ] 前端：Vite 初始化（Vue3 + JS）+ Tailwind + Element Plus + Pinia + axios，做基础布局
- [ ] **验收**：后端 `mvn spring-boot:run` 与前端 `npm run dev` 都能起，根路由 200，跨机调 LLM 成功

## TODO: 阶段 2 · 角色卡 CRUD
- [ ] 复用/重写 [`init.sql`](init.sql:1) 中 `role_card` 表，补 `is_active / tags / prompt_template / voice_id / hotwords` 字段
- [ ] `RoleCardService` + REST：`GET /api/roles`、`GET /api/roles/{id}`、`POST/PUT/DELETE`
- [ ] Prompt 模板：system = 人设 + 背景 + 性格 + 对话样例 + 输出风格约束
- [ ] 内置 2~3 个角色（含语音样本路径，给 PLAN-003 GPT-SoVITS 用）
- [ ] 前端：角色卡选择器 + 角色详情抽屉
- [ ] **验收**：切换角色后人设展示正确

## TODO: 阶段 3 · LLM 文字流式对话
- [ ] `LlmConfig` 配置 OpenAiChatModel / StreamingChatModel，base-url 指向 LM Studio
- [ ] `RolePlayAssistant` AIService 接口（`@SystemMessage` 动态注入角色人设）
- [ ] `ChatService.chatStream(ChatRequest)` → SSE 返回
- [ ] `ChatController` 校验（userId、conversationId、roleId、message 非空）
- [ ] 前端：对接 SSE，逐字打字机
- [ ] **验收**：文字输入 → 流式返回符合人设的中文回复

## TODO: 阶段 4 · 记忆管理
- [ ] 短期：LangChain4j `MessageWindowChatMemory`（窗口 20），Store 走 Redis
- [ ] 长期：`history` 表落盘（userId, conversationId, roleId, sender, content, ts）
- [ ] 摘要：每满 N 轮触发任务写 `memory` 表，下次进对话注入 system
- [ ] `ConversationService`：新建 / 列表 / 切换 / 删除 / 重命名
- [ ] **验收**：刷新后能恢复会话；超长会话不爆上下文

## TODO: 阶段 5 · RAG（角色台词 / 剧情）
- [ ] 语料按角色分目录：`resources/rag/{roleCode}/*.txt|md`
- [ ] 启动时为每个角色构建独立 EmbeddingStore（key=roleId）
- [ ] `RagService.retrieve(roleId, query, topK)` 注入 LangChain4j `ContentRetriever`
- [ ] AIService 按 roleId 路由到对应 retriever
- [ ] **RAG 默认常开，不在前端暴露开关**：所有角色卡 + 长期记忆都依赖 RAG，关闭等同"忘记角色设定"。`ChatRequest.rag` 默认 true，`application-local.yml` `rag.eager-init=true`，启动后台异步预热 + 首次提问最多同步等 30s。语音通道 [`AudioController`](src/main/java/org/example/aichat/controller/AudioController.java:1) 也开 RAG。
- [ ] **验收**：问"你记得那次战斗吗？"能命中台词并自然引用；首次冷启提问 RAG 仍能命中（异步预热已完成或同步等待 < 30s）

> **能力开关默认值汇总**（PLAN-008 排查手册同步维护）：
> | 开关 | 默认 | 前端展示 | 说明 |
> |---|---|---|---|
> | `rag` | **true** | 否 | 角色卡 + 长期记忆都依赖；关掉等于失忆 |
> | `tools` | **true** | 否 | 本地 MCP 工具（数学/素数/天气等），Gemma4 原生支持 tool-call |
> | `search` | false | **是**（联网 toggle） | 联网搜索可选，默认关，由用户按需开 |
> | 语音通道 `tools` | false | — | 语音侧追求首包延迟，强制关 |
> | 语音通道 `rag` | true | — | 与文字通道一致，否则 AI 答非所问 |

## TODO: 阶段 6 · ASR 接入（依赖 PLAN-002 已部署）
- [ ] 前置：[`PLAN-002`](.joycode/plans/PLAN-002-sensevoice-asr-deployment.md:1) 已跑通，SenseVoice HTTP `:9000` 可用
- [ ] 后端 `SenseVoiceClient`（OkHttp / WebClient）：multipart POST → 返回 `{text, lang, emotion?}`
- [ ] `application.yml` 加 `asr.provider=sensevoice`、`asr.base-url=http://127.0.0.1:9000`、`asr.language=auto`、`asr.timeout-ms=15000`
- [ ] `/api/audio/asr`：前端 webm → 后端转 16k mono wav（用 ffmpeg 或服务端自带）→ 调 SenseVoice
- [ ] 前端 `MediaRecorder` 按住说话，松手上传 → 文本回填输入框
- [ ] 角色热词：`roleCard.hotwords` 透传给 SenseVoice（若支持）
- [ ] **验收**：5s 中文短句录音 → 端到端 < 2.5s 拿到识别文本

## TODO: 阶段 7 · TTS 接入（依赖 PLAN-003 已部署）
- [ ] 前置：[`PLAN-003`](.joycode/plans/PLAN-003-gpt-sovits-streaming-tts.md:1) 已跑通，GPT-SoVITS 流式接口 `:9880` 可用
- [ ] 抽象 `TtsClient` 接口，实现 `GptSovitsStreamingClient`（chunk 转发）
- [ ] `voice_id` 与 `role_card.voice_id` 绑定，按角色选音色 + 参考音频
- [ ] `/api/audio/chat`（HTTP）：ASR→LLM 非流式整段 → TTS 流式 chunk 透传，响应头 `X-AI-Response-Text` 带回文本
- [ ] 前端：`MediaSource` 接 chunk，**第一个 chunk 到达即起播**，整段播完后 `endOfStream`
- [ ] **验收**：松开"按住说话"到听到 AI 第一个音 < 3.5s（包含 ASR + LLM 首句 + 首 chunk）

## TODO: 阶段 8 · 前端交互完善
- [ ] 角色卡网格选择页（封面、简述、标签）
- [ ] 对话界面：头像、气泡、打字机、语音波形占位、播放状态
- [ ] 会话列表：新建 / 删除 / 重命名
- [ ] 简单登录（用户名密码 + localStorage）
- [ ] 全局错误提示
- [ ] **验收**：非技术用户可独立"登录→选角色→按住说话→听到回答"

## TODO: 阶段 9 · 联调 & 部署 & 文档
- [ ] Mac 侧 docker-compose：mysql + redis + backend + frontend(nginx)；SenseVoice / GPT-SoVITS 走宿主机进程（要 Apple Silicon 加速，不进容器）
- [ ] [`frontend/nginx.conf`](frontend/nginx.conf:1) 配置 `/api` 反代后端，`/ws` 反代 WebSocket
- [ ] 可选 Tailscale，给 Win 拿固定虚拟 IP
- [ ] 回归脚本（httpx / Postman）：角色 / 对话 / ASR / TTS 各 1 条用例
- [ ] 更新 [`README.md`](README.md:1)：架构图、Mac/Win 启动步骤、环境变量清单
- [ ] **验收**：干净机器照 README 跑通

---

## 不在本 PLAN 范围内（去看 PLAN-004）

- 实时 VAD 切句、流式 ASR（边说边出字）
- barge-in 打断（说话期间被打断）
- 句级流水线（LLM token → 切句 → TTS chunk 逐句推）
- Tools 函数调用 / MCP
- Live2D 表情、动作、嘴型同步
- 弹幕、自言自语等多源输入

## 风险与备选

- LM Studio 不支持 function calling 或不稳定 → Tools 那部分到 PLAN-004 再决定走 LM Studio 自带 / 手搓 / 切云端
- Win 显存被 LLM 吃满 → 严禁在 Win 加 GPU 服务
- Mac 32GB 同一内存 → 业务 + SenseVoice + GPT-SoVITS 同时跑，要监控 `vm_stat`，必要时降级模型
- GPT-SoVITS 流式延迟不达标 → 回退 edge-tts 应急（仅作兜底）