# AI 对话项目（二次元角色扮演）

把游戏剧情与角色台词搬进现实，选择角色卡，开口就能聊。

## 技术栈

- 前端：Vue3 + Vite + Element Plus + Nginx
- 后端：Spring Boot 3 + LangChain4j + MyBatis
- LLM：本地 LM Studio（OpenAI 兼容协议）
- ASR：本地 SenseVoice
- TTS：edge-tts / GPT-SoVITS（按需替换）
- 存储：MySQL（角色卡、会话、历史、长期记忆）+ Redis（短期记忆）
- RAG：LangChain4j EmbeddingStore（角色台词与剧情向量化）

## 目录结构

```
.
├── src/main/java/org/example/aichat   # 后端代码
├── src/main/resources                 # application.yml / mapper / prompts / rag
├── frontend/                          # Vue3 工程
├── SenseVoice/                        # 本地 ASR 服务
├── prime-mcp-server/                  # MCP 工具服务（可选）
├── init.sql                           # 数据库一键初始化脚本
└── 项目规范.md                         # 项目目标与功能说明
```

## 快速开始

### 1. 准备依赖

| 组件 | 版本/说明 |
| --- | --- |
| JDK | 17+ |
| Maven | 用 `mvnw` 即可 |
| Node | 18+ |
| MySQL | 8.x，账号密码见 [`application-local.yml`](src/main/resources/application-local.yml) |
| Redis | 6+，本机 6379 |
| LM Studio | 启动一个 chat 模型 + 一个 embedding 模型（默认端口 1234） |
| SenseVoice | 见 [`SenseVoice/api.py`](SenseVoice/api.py) |

### 2. 初始化数据库

```bash
mysql -uroot -p < init.sql
```

### 3. 启动后端

```bash
./mvnw spring-boot:run
```

健康检查：`GET http://localhost:8080/api/health` 返回 `{"status":"UP",...}` 即骨架就绪。

### 4. 启动前端

```bash
cd frontend
npm install
npm run dev
```

打开 `http://localhost:3000`，登录后即可选择角色聊天。

## 重构计划

详见 [`PLAN-001-ai-chat-role-play-refactor.md`](.joycode/plans/PLAN-001-ai-chat-role-play-refactor.md)，按阶段交付，每阶段都有可验证的里程碑。

## License

MIT