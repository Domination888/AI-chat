# PLAN-001 · AI 二次元角色扮演对话项目重构

## Task Summary
基于 [`项目规范.md`](项目规范.md) 从 0 重构一个「选择角色卡 → 语音/文字对话」的 AI 项目。技术栈：前端 Vue3 + Nginx，后端 SpringBoot + LangChain4j，LLM 走本地 LM Studio（OpenAI 兼容协议），ASR 走本地 SenseVoice，TTS 走开源方案（GPT-SoVITS / edge-tts 等），支持角色卡构建、记忆管理、RAG 引入游戏剧情与台词。按阶段交付，每阶段结束后有可验证的里程碑。

## 技术选型（定稿）
- 后端：Spring Boot 3 + LangChain4j（OpenAI starter 指向 LM Studio）+ MyBatis + MySQL + Redis
- 前端：Vue3 + Vite + Pinia + Element Plus + Tailwind
- ASR：本地 [`SenseVoice/api.py`](SenseVoice/api.py)（FastAPI）
- TTS：GPT-SoVITS / edge-tts（二选一，默认 edge-tts 起步，后续替换）
- 记忆：Redis（短期滚动窗口）+ MySQL（长期消息历史 + 摘要）
- RAG：LangChain4j EmbeddingStore（内存版起步，后续可换 pgvector/Chroma）

## 里程碑验收点
- 阶段 3 后：文本对话端到端可跑
- 阶段 5 后：角色扮演质量达标（有人设 + 记忆 + 台词 RAG）
- 阶段 7 后：语音进 → 语音出 全链路闭环
- 阶段 9 后：一键启动 + README 可交付

---

## TODO: 阶段 1 · 项目骨架
- [ ] 清理遗留代码，保留 [`pom.xml`](pom.xml)、[`init.sql`](init.sql) 作参考
- [ ] 后端：规划包结构 `controller / service / repository / domain / infra(llm,asr,tts,rag,memory) / config / dto`
- [ ] 后端：创建 `application.yml`（dev/local/prod 三 profile，LM Studio base-url 可配）
- [ ] 前端：`npm create vite@latest`（Vue3 + JS），接入 Tailwind + Element Plus + Pinia + axios
- [ ] 前端：基础布局（左侧角色/会话，右侧对话区）
- [ ] 验证：`mvn spring-boot:run` 与 `npm run dev` 均能启动，根路由 200

## TODO: 阶段 2 · 角色卡 CRUD
- [ ] 复用/重写 [`init.sql`](init.sql) 中 `role_card` 表，补 `is_active / tags / prompt_template` 字段
- [ ] `RoleCardService` + REST：`GET /api/roles`、`GET /api/roles/{id}`、`POST/PUT/DELETE`
- [ ] 设计 Prompt 模板：system = 人设 + 背景 + 性格 + 对话样例 + 输出风格约束
- [ ] 内置 2~3 个角色（琉璃、原神类角色等），写入 seed SQL
- [ ] 前端：角色卡选择器 + 角色详情抽屉
- [ ] 验证：切换角色后，角色信息展示正确

## TODO: 阶段 3 · LLM 对话打通
- [ ] `LlmConfig` 配置 OpenAiChatModel / StreamingChatModel，base-url 指向 LM Studio
- [ ] 定义 `RolePlayAssistant` AIService 接口（含 `@SystemMessage` 动态注入）
- [ ] `ChatService.chatStream(ChatRequest)` → SSE 返回
- [ ] `ChatController` 参数校验（userId、conversationId、roleId、message 非空）
- [ ] 前端：对接 SSE，逐字打字机效果
- [ ] 验证：选角色 → 文字输入 → 获得符合人设的流式回复

## TODO: 阶段 4 · 记忆管理
- [ ] 短期记忆：LangChain4j `MessageWindowChatMemory`（窗口 20），Store 走 Redis
- [ ] 长期记忆：`history` 表落盘（userId, conversationId, roleId, sender, content, ts）
- [ ] 会话摘要：每满 N 轮触发摘要任务，写入 `memory` 表，下次进对话注入 system
- [ ] `ConversationService`：新建、列表、切换、删除、重命名
- [ ] 验证：刷新后仍能恢复会话；超长会话不会爆上下文

## TODO: 阶段 5 · RAG（角色台词 / 剧情）
- [ ] 按角色分目录存放语料：`resources/rag/{roleCode}/*.txt|md`
- [ ] 启动时对每个角色构建独立 EmbeddingStore（key = roleId）
- [ ] `RagService.retrieve(roleId, query, topK)` 注入 LangChain4j `ContentRetriever`
- [ ] 在 AIService 中按 roleId 路由到对应 retriever
- [ ] 前端：对话设置中可开关 RAG
- [ ] 验证：问角色「你记得那次战斗吗？」能命中台词片段并自然引用

## TODO: 阶段 6 · ASR 语音输入
- [ ] 确认 [`SenseVoice/api.py`](SenseVoice/api.py) 启动方式与接口协议
- [ ] 后端 `AsrClient`：POST 音频 → 返回文本（webm/wav 均支持，必要时 ffmpeg 转码）
- [ ] `/api/audio/asr` 单独暴露，便于前端测试
- [ ] 前端 `MediaRecorder` 录音（按住说话），上传后把文本塞入输入框
- [ ] 验证：中文普通话准确率可用，多轮连续识别不串

## TODO: 阶段 7 · TTS 语音输出
- [ ] 抽象 `TtsClient` 接口，先实现 edge-tts（零成本），预留 GPT-SoVITS 实现
- [ ] `voice_id` 与 `role_card.voice_id` 绑定，按角色选音色
- [ ] `/api/audio/chat`：ASR→LLM→TTS 一条龙，返回音频流 + `X-AI-Response-Text` 头
- [ ] 前端：自动播放 + 点击重播 + 切换「纯文字/语音双模」
- [ ] 验证：按住说话松手后，10s 内听到角色语音回答

## TODO: 阶段 8 · 前端交互完善
- [ ] 角色卡网格选择页（封面、简述、标签）
- [ ] 对话界面：头像、气泡、打字机、语音波形占位
- [ ] 会话列表：新建、删除、重命名
- [ ] 登录/注册（简单用户名密码，localStorage 保存）
- [ ] 全局错误提示（Element Plus Message）
- [ ] 验证：非技术用户可独立完成「登录→选角色→语音聊天」

## TODO: 阶段 9 · 联调 & 部署 & 文档
- [ ] `docker-compose.yml`：mysql + redis + sensevoice + backend + frontend(nginx)
- [ ] [`frontend/nginx.conf`](frontend/nginx.conf) 配置 `/api` 反代后端
- [ ] 回归测试脚本（Postman / httpx）：角色、对话、ASR、TTS 各 1 条用例
- [ ] 更新 [`README.md`](README.md)：架构图、启动步骤、环境变量清单
- [ ] 验证：干净机器按 README 从零跑通

---

## 风险与备选
- LM Studio 不支持 function calling → AIService 去掉 Tools，保持纯对话
- SenseVoice 本地部署资源不够 → 临时用 Whisper.cpp 替代
- GPT-SoVITS 训练成本高 → 先用 edge-tts，人设音色后期再换
- Embedding 模型选型 → 优先 LM Studio 本地 embedding，退路 `bge-small-zh`