# AI-Chat

AI-Chat 是一个本地优先的二次元角色对话桌面应用：Electron + Vue3 负责界面和 Live2D，Spring Boot 负责编排 LLM、RAG、Memos 长期记忆、ASR、TTS 和 MCP 工具。

## 当前技术栈

| 模块 | 当前实现 |
| --- | --- |
| 桌面端 | Electron 28 + Vue3 + Vite + Element Plus |
| 后端 | Spring Boot 3.5 + LangChain4j + MyBatis |
| LLM | OpenAI 兼容接口，默认通过运行时配置指向 LM Studio |
| ASR | 本地 SenseVoice，默认 `http://127.0.0.1:9000` |
| TTS | Astra/Genie-TTS，默认 `astra` 策略 |
| 记忆 | Memos 长期记忆 + Redis RAG fallback |
| 存储 | MySQL + Redis |
| 工具 | LangChain4j MCP，本地 SearXNG 搜索与 Prime 示例工具 |
| 打包 | electron-builder + `packaging/stage-runtime.sh` |

## 目录结构

```text
AI-Chat/
├── backend/                 # Spring Boot 后端
├── client/                  # Electron 主进程 + 打包配置
├── client/src/              # Vue3 前端源码
├── config/                  # 本机运行时配置、MCP 配置、本机服务参数
├── docs/                    # 当前项目文档
├── packaging/               # 全依赖安装包的缓存、模板、staging 脚本
├── scripts/                 # 构建、打包、数据库辅助脚本
├── services/                # SenseVoice、SearXNG MCP、Prime MCP 等服务
├── startup-scripts/         # 开发环境一键启动/停止脚本
└── unified-logs/            # 开发期统一日志
```

`MemOS/` 和 `my-neuro/` 是外部/参考工程，不作为 AI-Chat 主源码文档维护对象。

## 开发启动

前置依赖：

- JDK 17+
- Node 18+
- Python 3.10+
- MySQL 8.x 与 Redis 6+
- Docker（可选；开发期 SearXNG 使用 Docker）
- 可用的 LLM / Embedding / TTS OpenAI 兼容或 HTTP 服务

初始化数据库：

```bash
./scripts/local-db.sh mysql < backend/init.sql
```

启动完整开发环境：

```bash
./startup-scripts/start-all.sh
```

停止：

```bash
./startup-scripts/stop-all.sh
```

开发期默认地址：

| 服务 | 地址 |
| --- | --- |
| 前端 Vite | `http://localhost:3000` |
| 后端健康检查 | `http://localhost:8080/api/health` |
| ASR | `http://localhost:9000/healthz` |
| SearXNG | `http://localhost:8888` |
| MemOS | `http://localhost:8000` |

日志在 `unified-logs/` 下。

脚本入口说明见 `docs/scripts.md`。开发期不再维护按模块启动脚本，默认直接启动完整环境。

## 核心 API

- `POST /api/chat`：统一 SSE 聊天入口，支持文本/语音、RAG、MCP tools、联网搜索、TTS chunk。
- `POST /api/chat/interrupt`：打断当前会话生成和 TTS 播放。
- `POST /api/chat/proactive`：注册主动搭话。
- `GET /api/chat/proactive/stream`：主动搭话 SSE 流。
- `POST /api/audio/asr`：独立 ASR。
- `GET /api/audio/tts`：独立 TTS。
- `GET /api/rag/reload`：手动重建 RAG。
- `GET/PUT /api/runtime-config`：读取和保存运行时配置。
- `GET/PUT/POST/DELETE /api/roles`：角色卡管理。

## 构建与打包

构建后端、前端和 MCP JAR：

```bash
./scripts/build-all.sh
```

全依赖桌面安装包：

```bash
./scripts/package-all.sh mac
./scripts/package-all.sh win
```

打包版内置 Electron、后端 JAR、JRE、ASR、MySQL、Redis、MemOS、SearXNG、MCP JAR；LLM、Embedding、TTS 仍由用户在首次启动向导或设置页填写 URL。

更多细节见 `docs/packaging.md` 和 `docs/scripts.md`。
