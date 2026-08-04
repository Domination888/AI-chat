# 桌面应用打包指南

本文记录当前有效的 dmg / exe 打包链路。历史计划已清理，实际脚本以这里为准。

## 打包边界

| 随安装包内置 | 用户外部配置 |
| --- | --- |
| Electron + Vue 静态资源 | LLM OpenAI 兼容 URL |
| Spring Boot 后端 JAR + JRE | Embedding OpenAI 兼容 URL |
| SenseVoice ASR + 模型 + ffmpeg | Astra/Genie-TTS URL |
| MySQL / Redis 便携版 | |
| MemOS Python 服务 | |
| SearXNG Python 服务 | |
| Prime MCP JAR | |

LLM、Embedding、TTS 不随包分发，首次启动向导和设置页会写入 `runtime-config.json`。

## 前置条件

- JDK 17+，并配置 `JAVA_HOME`。
- Node 18+。
- Python 3.10+。
- Git（准备无 Docker 的打包版 SearXNG 官方源码时需要）。
- Maven 使用 `backend/mvnw`。
- `packaging/cache/<platform>/` 中准备好便携版 `redis`、`mysql`、`jre`，可选 `neo4j`、`qdrant`。
- ASR 模型在 `packaging/cache/asr-models/`。
- MemOS 模板若需要随包运行，应提前准备 venv；SearXNG 需运行 `packaging/templates/searxng/setup-venv.sh`（Windows 为 `.bat`），该脚本会拉取官方源码并安装其依赖，避免安装到 PyPI 上的同名 MCP 包。

## 一键打包

```bash
./scripts/package-all.sh mac
./scripts/package-all.sh win
```

脚本会依次执行：

1. `scripts/build-all.sh`：构建 Prime MCP JAR、后端 JAR、前端 dist。
2. 检查或下载 ASR 模型。
3. `packaging/stage-runtime.sh <platform>`：组装 `packaging/staging/<platform>`。
4. `electron-builder`：输出到 `client/release/`。

## 手动步骤

```bash
./scripts/build-all.sh
./packaging/stage-runtime.sh mac
cd client
npm install
npm run build:mac
```

Windows 同理使用 `./packaging/stage-runtime.sh win` 与 `npm run build:win`。

## 运行时行为

打包版由 `client/electron/service-manager.js` 编排服务：

1. 分配可用端口，不抢占用户已有进程。
2. 启动 Redis、MySQL、可选 Neo4j/Qdrant、MemOS、SearXNG、ASR、Backend。
3. 将实际端口写入 userData 下的 `service-ports.json`。
4. Electron 加载内置 `dist/index.html`。
5. 首次启动时通过 SetupWizard 配置 LLM / Embedding / TTS URL。

macOS 用户数据通常位于：

```text
~/Library/Application Support/AI-Chat/
```

日志位于 userData 的 `data/logs/`，也可通过客户端日志入口查看。

## Staging 结构

```text
packaging/staging/<platform>/
├── backend/AI-Chat-0.0.1-SNAPSHOT.jar
├── mcp/*.jar
├── jre/
├── redis/
├── mysql/
├── asr/
│   ├── models/
│   └── ffmpeg
├── memos/
└── searxng/
```

Electron builder 会把 staging 目录复制到安装包的 `Resources/runtime`。

## 打包前自检

```bash
./scripts/build-all.sh
bash packaging/stage-runtime.sh mac
test -x packaging/staging/mac/jre/bin/java
ls packaging/staging/mac/backend/AI-Chat-0.0.1-SNAPSHOT.jar
ls packaging/staging/mac/mcp/*.jar
ls packaging/staging/mac/asr/models
node --check client/electron/service-manager.js
```

如果 ASR 需要 webm 转码，确认：

```bash
test -x packaging/staging/mac/asr/ffmpeg
```

## 常见问题

### electron-builder 下载超时

脚本默认使用 npmmirror。也可手动指定：

```bash
export ELECTRON_MIRROR=https://npmmirror.com/mirrors/electron/
export ELECTRON_BUILDER_BINARIES_MIRROR=https://npmmirror.com/mirrors/electron-builder-binaries/
./scripts/package-all.sh mac
```

### 选角色或登录失败

先看后端日志和 MySQL 日志。常见原因：

- MySQL 未启动或端口写错。
- 首次导入 `init.sql` 编码不对。
- JDBC `characterEncoding` 被错误写成 `utf8mb4`，应为 `UTF-8`。

### ASR 失败

确认 ASR 模型和 ffmpeg 已随包：

```bash
ls packaging/staging/mac/asr/models
test -x packaging/staging/mac/asr/ffmpeg
```

### 联网搜索不可用

确认 SearXNG JSON 接口可用；后端 Search-RAG 会直接访问它，不再经过 SearXNG MCP：

```bash
curl 'http://127.0.0.1:8888/search?q=test&format=json'
```

开发期还需要 Docker 能启动 `services/searxng/docker-compose.yml`。

### TTS 不出声

打包版不会启动本地 TTS。检查设置页里的 Astra/Genie-TTS URL 是否可达，默认接口应支持 `/api/tts/predict-stream`。
