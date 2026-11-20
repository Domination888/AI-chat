# 从 MySQL / Redis 理解当前技术栈

本文面向熟悉 MySQL、Redis，但尚未用过 PostgreSQL、pgvector、SQLite 和本地推理运行时的开发者。项目只分**商业版**和**离线版**：商业版包含用户端、管理端及它们共用的后端；离线版是一套完整桌面应用。代码中的 `cloud` 表示商业版，`offline` 表示离线版，`admin` 表示商业版管理端的前端构建目标。

## 1. 先看哪些没变、哪些变了

Vue 仍负责界面，Electron 仍是桌面外壳，Java / Spring Boot 仍处理业务。最大的变化在于持久化、模型调用、记忆以及服务编排。没有把整个项目换成一门新语言。

| 原版的技术栈 | 当前实现（`apps/`） | 可以怎样理解 |
| --- | --- | --- |
| Spring Boot + MyBatis | Spring Boot + JdbcTemplate | 仍写 SQL；从 Mapper/XML 变成 Java 中直接调用 SQL |
| MySQL | 商业版 PostgreSQL；离线版 SQLite | 仍有表、索引、事务、SQL；按多人服务和单机应用分别选数据库 |
| Redis、外部 MemOS 等记忆依赖 | SQL 中的索引任务、记忆表 + 进程内并发控制 | 将任务可靠性留在数据库；部分协调仅在当前进程有效 |
| LangChain4j 等模型封装 | `OpenAiTransport` + Java HttpClient | 直接发送 OpenAI 兼容 HTTP 请求并解析流 |
| 外部记忆框架 / 向量服务组合 | PostgreSQL 内 pgvector；SQLite BLOB + Java 余弦计算 | 向量与故事数据保存在同一个关系库内 |
| MySQL/Redis/语音等多服务组合启动 | 离线桌面主进程启动 Java、llama-server、语音进程 | 安装后由应用管理子进程，用户不另装数据库服务器 |
| 兼容界面的 Live2D、搜索/MCP、主动研究 | 当前发行界面围绕角色、故事、世界状态和分支 | 原功能仍可在兼容代码中查看，不能推断已迁入新界面 |

这里的变化只描述当前工程。不是说新方案在所有场景都比旧方案强；尤其不能将原 MemOS 的全部功能视为已被完整替代。

## 2. PostgreSQL：先当成另一种关系数据库

商业版中，PostgreSQL 承担原先 MySQL 存用户、角色、会话、消息等表的工作。连接方式仍是 JDBC，连接池仍是 Spring 管理的 HikariCP；事务仍可使用 `@Transactional`。你的表设计、索引、参数化 SQL 和事务知识仍然适用。

本项目常见的差别：

| MySQL 中常见写法 | 本项目 PostgreSQL 中的写法 |
| --- | --- |
| `AUTO_INCREMENT` | `BIGSERIAL` 自动生成编号 |
| `INSERT ... ON DUPLICATE KEY UPDATE` | `INSERT ... ON CONFLICT (...) DO UPDATE` |
| 插入后另外取生成 ID | `INSERT ... RETURNING id` 可以直接返回 ID |
| 查询某个用户的会话 | 同样 `WHERE user_id=?`，仍需业务层检查所有权 |
| 通过迁移工具建表 | 本项目自写 `Migrations`，执行 SQL 并记录 SHA-256 校验值 |

实际结构在 `apps/server/src/main/resources/db/V1__postgres.sql`。不要把 MySQL 的 dump 直接导入 PostgreSQL；项目通过 JSON 导出、编号映射和导入迁移故事，详见 [数据迁移](migration.md)。图中的关系是业务关联，不代表 SQL 已为每条关联声明外键。

## 3. pgvector：装进 PostgreSQL 的“相似含义检索”扩展

普通 SQL 擅长查“等于某个词”。向量检索则是先用嵌入模型把一段话变成一串数，再比较两串数的方向，找含义相近的原文。例如用户说“你还记得我们在码头的约定吗”，可以尝试找回之前的相关对话，即使措辞不同。

```text
已完成对话 → 嵌入模型 → 1024 个数 → memory_entry.embedding
本次问题   → 同一嵌入模型 → 1024 个数 → 排序找相关原文 → 加入聊天提示词
```

pgvector 是 PostgreSQL 扩展，不是另启动一个向量数据库。本项目迁移执行 `CREATE EXTENSION vector`，使用 `vector(1024)` 列；`embedding <=> 查询向量` 表示余弦距离，**越小越接近**。先按世界和模型过滤，再取前三条。

当前没有创建 HNSW 或 IVFFlat 近似向量索引，主要索引是普通世界索引，属于精确检索方案。不要把“用了 pgvector”理解为已经部署大规模近似检索。嵌入维度必须符合当前 1024 维 schema，换模型还需重建向量；仅名称相同也不能保证两个模型版本的向量空间一致。

参考：[pgvector 官方说明](https://github.com/pgvector/pgvector)。项目实现见 `MemoryIndex.java` 与 `OpenAiTransport.java`。

## 4. SQLite：数据库直接嵌在离线应用里

SQLite 是一个嵌入式 SQL 数据库。本项目通过 SQLite JDBC 驱动在 Java 进程内读写 `stories.sqlite`，无需启动数据库服务、配置 3306/5432 端口、创建数据库账号。

离线版的角色、会话、原文、世界、索引任务、配置等仍在表里。图片等二进制对象单独写本地文件。SQLite 不是把所有内容塞入 JSON，也不是只存在内存中。

**WAL** 是预写日志模式：提交先记录到 WAL，再由检查点并回主数据库。它允许读取与写入更好地并行，但仍然只有一个写者；本项目离线连接池大小还限制为 1。因此它适合当前单用户桌面场景，不能据此推断已经具备多人高并发写入能力。运行中可能同时存在 `stories.sqlite`、`stories.sqlite-wal`、`stories.sqlite-shm`，不要在运行时只复制主文件当成完整备份。用导出接口备份故事，或停掉应用后备份完整数据目录。参见 [SQLite WAL](https://www.sqlite.org/wal.html)。

离线的向量存成 BLOB，Java 从当前世界最新 1000 条同模型记忆中计算余弦相似度，取分数大于 0.45 的前三条。这不是 SQLite 内置 pgvector，也没有离线向量服务器。

**FTS5** 是 SQLite 的全文检索扩展。项目建立 `memory_fts` 虚拟表，以 trigram（连续三个字符）切词，并通过触发器同步记忆原文，作为向量检索以外的补充。它查文字片段，无法代替语义检索；少于三个字符的查询也不应期待同样效果。最终两路结果去重，最多注入四条。参见 [SQLite FTS5](https://www.sqlite.org/fts5.html)。

## 5. Redis 去哪里了

当前发行版不要求 Redis，但它的职责没有被一个新组件一对一替换：

| 需求 | 当前实际实现 | 限制 |
| --- | --- | --- |
| 消息与世界状态持久化 | SQL 表、事务和回合快照 | 以数据库原文与快照为准 |
| 等待生成向量的任务 | SQL `index_queue`，定时轮询、退避重试 | 当前没有多消费者租约协议 |
| 账号 access/refresh 会话 | SQL `auth_session`，保存令牌哈希 | 不是 Redis Session，也不是 JWT |
| 每个用户/世界的活动生成、世界锁 | `Streams` 内存映射与锁 | 仅协调一个 Java 实例 |
| 认证尝试、接口限流 | Java 进程内计数 | 重启会重置，跨实例不共享 |
| 每日额度与累计用量 | SQL `daily_usage` | 要与临时的每分钟限流区分 |

schema 中保留的 `memos_cube_id` 字段和 `memory_outbox` 表属于兼容结构，不表示当前仍连接 MemOS；发行记忆消费者读取的是 `index_queue`。

这能减少单用户离线安装与小规模商业测试的服务依赖，但不能把它直接横向扩成多个 API 实例。若将来扩容，需要补充共享限流、队列任务领取、跨实例中断和锁协调，Redis 也可能成为候选。

## 6. S3 / MinIO：保存文件，不替代关系数据库

可以把对象存储理解为通过 HTTP 按 key 读写文件的服务。商业版把图片字节写入 S3 兼容存储；Compose 使用 MinIO 来提供这套接口。数据库只记录所有者、文件类型、大小与会话引用。

例如一张图片可能对应对象 key `用户编号/随机附件编号`。业务 API 验证访问权限，再读出图片；客户端不拿到对象存储的访问密钥。故事分支可以引用同一附件，删除会话时还要判断是否有其他引用。

离线版实现同一个 `ObjectStore` 接口，但把对象写到数据目录。这类似你以前“数据库存元信息、硬盘存图片”的结构，只是商业版把硬盘接口换成了对象接口。备份商业版时必须同时备份 PostgreSQL、对象数据和加密密钥。参见 [S3 官方概念](https://docs.aws.amazon.com/AmazonS3/latest/userguide/Welcome.html)。

## 7. llama.cpp、GGUF、ONNX 分别是什么

| 名词 | 在项目中的角色 |
| --- | --- |
| llama.cpp / `llama-server` | 本机推理程序，加载聊天或嵌入模型并提供 HTTP API |
| GGUF | llama.cpp 使用的模型文件格式；权重不是 Java 代码或数据库 |
| Qwen3-4B | 默认离线聊天模型，生成角色回复；当前默认是文本模型 |
| Qwen3-Embedding-0.6B | 默认嵌入模型，把原文转换成检索向量 |
| ONNX / sherpa-onnx | 本机语音推理格式与执行库 |
| SenseVoiceSmall / Kokoro | 分别负责语音识别与语音合成 |
| `story-voice` | 将 Python 语音服务和依赖冻结后的可执行程序 |
| ffmpeg | 把录音解码成语音模型可处理的音频 |

Java 不直接把 GGUF 加载进 JVM；它通过 OpenAI 兼容接口调用本机模型服务。正常导入离线模型包后，Electron 启动两个 llama-server（聊天、嵌入各一个）和一个语音服务。商业版则由后端按平台配置调用模型服务。

“打包完整应用”和“附带模型权重”是两件事：离线安装包包含 Java、推理和语音运行时，权重单独签名分发。未导入模型包时仍可启动前后端、管理角色和导入故事；要实际聊天，需导入签名模型包，或在设置中连接可用的本地模型。语音和向量索引也需要对应模型服务。公网 API 必须由离线用户明确开启。

## 8. 从一条消息串起底层实现

1. `ReleaseApp.vue` 调用 `POST /api/chat`。Electron 通过受控 IPC 提供服务地址和本机令牌，页面不能任意操作操作系统。
2. `SecurityConfiguration` 从令牌确定身份，`StoryStore` 校验角色、篇章和世界的归属。
3. `Streams` 注册活动流并取得世界锁。用户原文与角色占位消息先写入 `history`。
4. `ChatApi` 收集角色资料、当前世界状态、最近历史和相关记忆，`PromptBudget` 限制输入预算。
5. `OpenAiTransport` 发出流式 HTTP 请求；`RpOutputParser` 分离可见文本和私有场景更新；SSE 把文字逐步送回界面。
6. `StoryStore.finish` 保存实际回复与 `rp_turn` 快照；提交回合同时加入 `index_queue`，消费者只索引完整回复。被中断的回复保留已生成原文，但不应用未完成的场景更新。
7. `MemoryIndex` 后台生成向量并保存。下次召回还检查原回合存在，避免已回退的故事重新进入记忆。

你可以依次阅读 `ChatApi → StoryStore → packages/rp/.../RpSessionService → MemoryIndex → OpenAiTransport`。这些类位于 `apps/server/src/main/java/org/example/aichat/release/`，共享 RP 类位于 `packages/rp/src/main/java/org/example/aichat/rp/`。更详细的边界见 [架构](architecture.md) 和 [实现](implementation.md)。

## 9. 日常只记住这些入口

| 目的 | 根目录命令 |
| --- | --- |
| 运行离线版前后端 | `npm run dev:offline`（`npm run dev` 等价） |
| 运行商业版用户端、管理端、后端及测试基础服务 | `npm run dev:commercial` |
| 以管理端浏览器为入口启动商业版测试环境 | `npm run dev:admin` |
| 打包商业版用户端 | `npm run package:commercial:user` |
| 打包商业版管理端和共用后端部署包 | `npm run package:commercial:admin` |
| 打包离线版整体 | `npm run package:offline` |

首次开发命令自动安装缺失的前端依赖并构建最新后端。商业版需要 Docker 已启动；离线启动只需 Node/JDK，完整原生打包另需构建工具。所有环境前提、产物位置、开发账号和模型准备步骤见 [打包与运行](packaging.md)。
