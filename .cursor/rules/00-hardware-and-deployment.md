# 硬件与部署事实

本文件是后续规划和编码的硬约束。过时的方案对比、实验计划不放在这里。

## 机器分工

| 服务 | 当前部署 | 说明 |
| --- | --- | --- |
| Electron / Vue 前端 | Mac | 开发和桌面客户端主入口 |
| Spring Boot 后端 | Mac | 业务 API、RAG、MCP、TTS/ASR 编排 |
| MySQL / Redis | Mac | 开发期本机服务，打包版使用随包便携服务 |
| SenseVoice ASR | Mac | `services/sense-voice/server.py`，默认 `:9000` |
| LLM | Win 或用户配置的 OpenAI 兼容 URL | 默认通过运行时配置指向 LM Studio |
| Embedding | 当前运行时配置 | 目标是 Mac 常驻轻量 embedding，避免占用 Win GPU |
| Astra/Genie-TTS | Win | 默认 `:5000`，Mac 只做健康检查 |
| SearXNG | 开发期 Docker，打包版随包 Python 服务 | MCP 联网搜索依赖它 |
| MemOS | 开发期本机 `:8000`，打包版随包服务 | 长期记忆来源 |

## 关键约束

- Win GPU 主要留给 LLM，不要再叠加 ASR、Embedding、Reranker 等常驻 GPU 服务。
- Mac 端适合跑业务后端、ASR、数据库、RAG 索引和轻量 embedding。
- TTS 当前以 Win Astra/Genie-TTS 为准，不再恢复 Mac GPT-SoVITS 或 MLX-Audio 作为默认路径。
- LLM 使用 OpenAI 兼容接口，后端自己裁剪 prompt；不要依赖超大上下文来解决上下文管理问题。
- 打包版不内置 LLM、Embedding、TTS 模型，只内置 Electron、后端、ASR、MySQL、Redis、MemOS、SearXNG、MCP JAR 和 JRE。

## 本机服务连接

连接参数以 `config/local-services.env` 和 `backend/src/main/resources/application-local.yml` 为准。

```bash
./scripts/local-db.sh status
./scripts/local-db.sh mysql -e "SELECT id, name, role_code FROM role_card;"
./scripts/local-db.sh redis ping
```

常用端口：

| 服务 | 端口 |
| --- | --- |
| Backend | 8080 |
| Frontend Vite | 3000 |
| ASR | 9000 |
| MemOS | 8000 |
| SearXNG | 8888 |
| MySQL | 3306 |
| Redis | 6379 |
