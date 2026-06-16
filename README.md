# AI 对话项目（二次元角色扮演）- Electron 客户端版

把游戏剧情与角色台词搬进现实，选择角色卡，开口就能聊。现在支持桌面客户端！

## 技术栈

- 客户端：Electron + Vue3 + Vite + Element Plus
- 后端：Spring Boot 3 + LangChain4j + MyBatis
- LLM：本地 LM Studio（OpenAI 兼容协议）
- ASR：本地 SenseVoice
- TTS: 本地 GPT-SoVITS
- 存储：MySQL（角色卡、会话、历史、长期记忆）+ Redis（短期记忆）
- RAG：LangChain4j EmbeddingStore（角色台词与剧情向量化）

## 新的目录结构

```
AI-Chat/
├── backend/                    # Spring Boot后端
│   ├── src/
│   ├── pom.xml
│   └── ...
├── client/                     # Electron客户端应用
│   ├── electron/               # Electron主进程代码
│   ├── src/                    # Vue3前端代码
│   ├── package.json            # Electron应用配置
│   └── ...
├── services/                   # 独立服务
│   ├── gpt-sovits/             # GPT-SoVITS服务
│   ├── sense-voice/            # SenseVoice ASR服务
│   └── prime-mcp-server/       # MCP工具服务
├── docs/                       # 项目文档
└── scripts/                    # 部署和构建脚本
```

## 快速开始

### 1. 准备依赖

| 组件 | 版本/说明 |
| --- | --- |
| JDK | 17+ |
| Maven | 用 `mvnw` 即可 |
| Node | 18+ |
| MySQL | 8.x，账号密码见 [`backend/src/main/resources/application-local.yml`](backend/src/main/resources/application-local.yml) |
| Redis | 6+，本机 6379 |
| LM Studio | 启动一个 chat 模型 + 一个 embedding 模型（默认端口 1234） |
| SenseVoice | 见 [`services/sense-voice/api.py`](services/sense-voice/api.py) |

### 2. 初始化数据库

```bash
# 推荐：项目脚本（不依赖 mysql 是否在 PATH）
./scripts/local-db.sh mysql < backend/init.sql

# 或直连（需 ~/.zshrc 已配置 MySQL PATH）
mysql -uroot -p < backend/init.sql
```

本机 MySQL / Redis 连接说明见 [`AGENTS.md`](AGENTS.md) 与 [`config/local-services.env`](config/local-services.env)。

### 3. 一键启动开发环境

```bash
# 启动完整的开发环境（后端 + 前端 + Electron）
./run-dev.sh

# 或者使用脚本（推荐）
./scripts/start-dev.sh
```

### 4. 手动启动（可选）

如果需要手动控制各个组件：

```bash
# 1. 启动后端
cd backend && ./mvnw spring-boot:run

# 2. 启动前端开发服务器
cd client/src && npm run dev

# 3. 启动Electron客户端
cd client && npx electron .
```

### 5. 停止服务

```bash
./stop-dev.sh
```

## 开发指南

### Electron客户端开发

客户端使用Electron + Vue3技术栈：

1. **主进程代码**：`client/electron/main.js`
2. **渲染进程代码**：`client/src/`（Vue3应用）
3. **IPC通信**：通过`client/electron/preload.js`暴露API

### 后端API

后端提供RESTful API，客户端通过HTTP请求与后端通信。主要端点：

- `GET /api/health` - 健康检查
- `POST /api/chat` - 聊天接口
- `GET /api/characters` - 获取角色列表
- `POST /api/asr` - 语音识别
- `POST /api/tts` - 文本转语音

## 构建和部署

### 开发环境
```bash
./run-dev.sh
```

### 生产构建
```bash
# 全依赖 dmg / exe（详见 docs/packaging.md）
./scripts/package-all.sh mac
./scripts/package-all.sh win

# 仅构建前端 + Electron 安装包（需先 stage-runtime）
cd client && npm run build:mac      # macOS
cd client && npm run build:win       # Windows
```
