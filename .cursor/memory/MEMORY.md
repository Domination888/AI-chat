# AI-Chat 当前项目记忆

本文件只保留会影响后续开发判断的长期事实。历史实施过程和已完成方案不再放在 memory 中。

## 运行边界

- 项目是 Electron + Vue3 桌面客户端，后端是 Spring Boot 3.5 + LangChain4j + MyBatis。
- 开发模式入口是 `./startup-scripts/start-all.sh`，停止入口是 `./startup-scripts/stop-all.sh`。
- 打包入口是 `./scripts/package-all.sh mac` 或 `./scripts/package-all.sh win`。
- LLM、Embedding、TTS 的 URL 通过 `config/runtime-config.json` 和设置页持久化，后端默认配置在 `backend/src/main/resources/application-local.yml` 与 `application-packaged.yml`。

## TTS

- 当前唯一后端 TTS 引擎是 `astra`，实现为 `backend/src/main/java/org/example/aichat/service/tts/AstraTtsStrategy.java`。
- Astra/Genie-TTS 部署在 Win 机器上，HTTP 接口为 `/api/tts/predict-stream`，流式返回 32kHz 单声道 `pcm_f32le`。
- Mac 端不再启动本地 TTS；`startup-scripts/start-tts.sh` 只做 Win TTS 健康检查。
- 角色音色通过 `voice.tts-profiles` 映射，黍默认使用 `Shu_v2proplus`。

## 记忆与 RAG

- Memos 服务默认 `http://localhost:8000`，作为长期记忆来源；Memos 不可用时可 fallback 到 Redis RAG。
- 写入 Memos 时只写用户原话，避免 assistant 幻觉污染 UserMemory。
- 当前固定 Memos `user-id` 为 `8736b16e-1d20-4163-980b-a5063c3facdc`。
- 当前默认 cube 为 `b32d0977-435d-4828-a86f-4f47f8b55bca`。
- RAG 语料位于 `backend/src/main/resources/personas/<roleCode>/`，黍已有 `persona_card.json`、`memory_cards.jsonl` 与 `lore/*.md`。
- 开发配置中 `rag.force-rebuild-on-startup: true`，改 persona 或 lore 后重启后端即可重建索引。

## Live2D 与主动对话

- Live2D 已在 Electron 透明子窗口中渲染，入口为 `client/src/src/components/Live2DOverlay.vue`。
- 情绪标签由后端剥离并发送 `emotion` SSE 事件；前端映射在 `client/src/src/live2d/emotion-mappings.js`。
- 对话打断入口是 `POST /api/chat/interrupt`。
- 主动搭话入口包括 `POST /api/chat/proactive`、`GET /api/chat/proactive/stream` 和 `POST /api/chat/proactive/trigger`。

## 本机服务

- MySQL / Redis 连接信息以 `config/local-services.env` 为准。
- Agent 查询数据库优先使用 `./scripts/local-db.sh`，避免 PATH 差异。
