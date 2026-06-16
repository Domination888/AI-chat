# 桌面应用打包指南

将 AI-Chat 打包为 **全依赖 dmg（macOS）/ exe（Windows）**。LLM、Embedding、TTS 由用户独立部署，通过首次启动向导或设置页填写 URL；其余服务随安装包启动。

## 打包边界

| 随包内置 | 外部 URL |
|----------|----------|
| Electron + Vue 前端 | LLM（LM Studio 等） |
| Spring Boot + JRE | Embedding API |
| SenseVoice ASR + 模型权重 | TTS（Astra 等） |
| MySQL / Redis / Neo4j / Qdrant 便携版 | |
| MemOS + SearXNG | |
| MCP JAR（联网搜索） | |

## 前置条件

- JDK 17+（`JAVA_HOME`，用于 jlink / 后端构建）
- Node 18+、Maven（`backend/mvnw`）
- Python 3.10+（ASR 模型下载、MemOS/SearXNG venv）
- 磁盘空间：建议 ≥ 15GB（含 ASR 模型与数据库二进制）

## 一次性准备

### 1. 下载便携版基础设施

```bash
./packaging/fetch-runtime.sh mac   # 或 win
# 按提示将 redis/ mysql/ neo4j/ qdrant/ jre/ 放入 packaging/cache/<platform>/
```

### 2. 下载 SenseVoice 模型

```bash
chmod +x scripts/download-asr-models.sh
./scripts/download-asr-models.sh
```

### 3. 构建 ASR 二进制（可选，否则 dev 模式用 python server.py）

```bash
cd services/sense-voice
./build-mac.sh    # macOS
# ./build-win.sh  # Windows 上执行
```

### 4. MemOS / SearXNG Python 环境（打包前）

```bash
./packaging/templates/memos/setup-venv.sh
./packaging/templates/searxng/setup-venv.sh
```

## 一键打包

```bash
chmod +x scripts/package-all.sh packaging/stage-runtime.sh
./scripts/package-all.sh mac    # → client/release/*.dmg
./scripts/package-all.sh win    # → client/release/*.exe (需在 Windows 构建)
```

等价手动步骤：

```bash
./scripts/build-all.sh
./packaging/stage-runtime.sh mac
cd client && npm install && npm run build:mac
```

## 运行时行为

1. 用户启动 **AI-Chat.app** / 安装目录中的 exe  
2. Electron **ServiceManager** 按序拉起：Redis → MySQL → Neo4j → Qdrant → MemOS → SearXNG → ASR → Backend  
3. 加载内嵌 `dist/index.html`，API 指向 `http://127.0.0.1:8080`  
4. 首次启动弹出 **SetupWizard**，配置 LLM / Embedding / TTS URL  
5. 用户数据与日志：`~/Library/Application Support/AI-Chat/data/`（macOS）或 `%APPDATA%/AI-Chat/data/`（Windows）

## 目录结构（安装包内 Resources/runtime）

```
runtime/
├── jre/
├── backend/AI-Chat-0.0.1-SNAPSHOT.jar
├── mcp/*.jar
├── redis/ mysql/ neo4j/ qdrant/
├── memos/ searxng/
└── asr/sensevoice-server + models/
```

## 端口与进程

- **动态端口**：默认 3306/6379/8080 等若已被其它程序占用，启动时会从默认值起自动顺延到空闲端口，**不会抢占或结束**本机已有服务。
- 实际端口写入 `~/Library/Application Support/AI-Chat/data/service-ports.json`。
- **退出清理**：关闭应用（或关闭所有窗口）时会 SIGTERM/SIGKILL 整个进程组，确保内置 MySQL/Redis/Neo4j 等不会残留后台。

## 依赖清单（勿精简）

打包版 = **开发环境整栈复制**，任何一环缺失都会在运行时暴露，且 dev 模式（Vite 代理 + 本机已有 MySQL）往往测不到。

| 组件 | 必需内容 | 精简/遗漏时的症状 |
|------|----------|-------------------|
| **Electron 前端** | `dist/` + `app://` 协议 | UI 无样式、Live2D 不加载 |
| **Tailwind/PostCSS** | `client/` 层 devDependencies + vite 显式 postcss 配置 | 界面只剩裸 HTML |
| **JRE** | 完整 JRE 或 jlink 含 `java.desktop,java.sql` | 后端启动 `PropertyEditorSupport` 崩溃 |
| **MySQL** | 便携 mysqld + `init.sql` **utf8mb4 导入** + JDBC `characterEncoding=UTF-8`（不是 `utf8mb4`） | 中文乱码；JDBC 报 `UnsupportedEncodingException: utf8mb4` → **选角色/登录全失败** |
| **Redis** | redis-server 二进制 | RAG 缓存失败（可降级，但性能差） |
| **Neo4j + Qdrant** | 二进制 + 用户 data 目录 | MemOS 记忆检索失败 |
| **MemOS venv** | 完整 Python 依赖 + 源码 | `memos.log` 报错，长期记忆不可用 |
| **SearXNG venv** | 完整 Python 依赖 | 联网搜索 MCP 不可用 |
| **ASR venv** | funasr + torch + **ffmpeg**（`imageio-ffmpeg` 或 `runtime/asr/ffmpeg`） | 语音输入 400 /「无法识别音频内容」 |
| **ASR 模型** | SenseVoiceSmall + VAD 权重 | 启动卡在 ASR 或识别空结果 |
| **Backend JAR** | Spring Boot fat jar + `personas/` 资源 | 角色/对话 API 500 |
| **MCP JAR** | searxng + prime + **bundled JRE 的 java** | 工具调用失败 |
| **CORS** | 后端 `WebCorsConfig` + Electron localhost 头注入 | 保存配置 / API 调用 `Failed to fetch` |

### 为何 dev 正常、打包总出问题

1. **协议不同**：dev 用 `http://localhost:3000` + Vite 代理；打包用 `app://` + 直连 `127.0.0.1`，CORS / 静态资源规则完全不同。  
2. **路径不同**：`AI_CHAT_HOME` / `AI_CHAT_DATA` 与 `backend/` cwd 两套路径，配置/MCP/日志易写错位置。  
3. **子进程栈**：8 个服务串行启动，任一崩溃若未 health check，后端仍会起来但 DB/API 全挂。  
4. **版本差异**：便携 MySQL 8.4 不支持旧选项（如 `skip-character-set-client-handshake`），dev 若用 Homebrew MySQL 5.7/8.0 不会复现。  
5. **构建链路过长**：`build-all → stage-runtime → electron-builder`，前端/后端/ASR 任一未重编即装旧逻辑。

### 打包前自检

```bash
# 1. 基础设施 cache 齐全
ls packaging/cache/mac/{redis,mysql,neo4j,qdrant,jre}

# 2. ASR 含 ffmpeg 与模型
ls packaging/cache/asr-bundle/ffmpeg
ls packaging/cache/asr-models/models/iic/SenseVoiceSmall/model.pt

# 3. stage 后抽查
bash packaging/stage-runtime.sh mac
ls packaging/staging/mac/asr/ffmpeg
test -x packaging/staging/mac/jre/bin/java && packaging/staging/mac/jre/bin/java -version

# 4. 语法/构建
node --check client/electron/service-manager.js
./scripts/build-all.sh
```

## 故障排查

- **选角色/登录失败、API 500**：先看 `backend.log` 是否有 `UnsupportedEncodingException: utf8mb4`（JDBC 应写 `characterEncoding=UTF-8`）或 `HikariPool` 连库失败；再看 `mysql.log` 是否 `Aborting`。
- **角色名中文乱码**（如 `äºŒé˜¶`）：打包版 MySQL 首次导入未指定 `utf8mb4` 导致。新版本启动时会自动执行 `fix-encoding.sql`；也可手动：
  ```bash
  ./packaging/staging/mac/mysql/bin/mysql --default-character-set=utf8mb4 -uroot -h127.0.0.1 -P<mysql端口> < packaging/config/fix-encoding.sql
  ```
  端口见 `~/Library/Application Support/ai-chat-electron/data/service-ports.json`。
- **语音识别失败 / ASR 400**：Electron 录音为 webm，ASR 需 **ffmpeg** 转码。请重新运行 `packaging/templates/asr/setup-asr-bundle.sh` 后再打包，确保 `runtime/asr/ffmpeg` 存在。
- **electron-builder 下载 Electron 超时**（`connect: operation timed out`）：默认已配置 npmmirror；也可手动指定：
  ```bash
  export ELECTRON_MIRROR=https://npmmirror.com/mirrors/electron/
  export ELECTRON_BUILDER_BINARIES_MIRROR=https://npmmirror.com/mirrors/electron-builder-binaries/
  ./scripts/package-all.sh mac
  ```
- 日志：`Application Support/AI-Chat/data/logs/`（托盘菜单「打开日志目录」）
- 端口冲突：3306 / 6379 / 7474 / 6333 / 8000 / 8888 / 9000 / 8080  
- MemOS 依赖 Neo4j + Qdrant 先就绪；启动失败查看 `memos.log`  
- ASR 首次加载模型较慢，见 `asr.log`

## 开发模式（不打包）

仍使用 `./startup-scripts/start-all.sh`；Electron `npm run dev` 连接 `localhost:3000` Vite，不启动 ServiceManager。
