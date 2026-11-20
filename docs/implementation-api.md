# 技术实现 · 7. 主要 API

[返回技术实现](implementation.md)

## 7. 主要 API

| 方法与路径 | 功能 |
| --- | --- |
| `GET /api/health` | 模式与 schema 就绪信息 |
| `GET /api/v1/me` | 当前身份及近期用量 |
| `POST /api/v1/auth/{email-code,register,login,admin-login,refresh,logout}` | 云认证 |
| `GET/POST /api/roles`、`PUT/DELETE /api/roles/{id}` | 角色库与私有角色 |
| `GET /api/v1/conversations` | 账号篇章列表 |
| `GET /api/conversation/{id}/history` | before/limit 分页与文本搜索 |
| `DELETE /api/conversation/{id}` | 删除篇章 |
| `POST /api/rp/sessions`、`GET/PUT /api/rp/{id}` | 新建与场景设置 |
| `POST /api/rp/{id}/{branch,rewind}/{message}` | 分支与回退 |
| `PUT /api/rp/{id}/bookmark/{turn}` | 收藏 |
| `GET /api/rp/{id}/{album,memory-delivery}` | 相册与索引状态 |
| `POST /api/rp/{id}/memory-retry` | 重试失败索引 |
| `POST /api/chat`、`POST /api/chat/interrupt` | 统一输入、SSE 与中断 |
| `GET/PUT/DELETE /api/v1/me/model-connection` | 模型连接 |
| `POST /api/v1/me/model-connection/test` | 测试已保存连接 |
| `POST /api/v1/voice/speech` | 生成朗读 WAV |
| `GET/DELETE /api/v1/me/data` | 导出或删除私人数据 |
| `POST /api/v1/offline/import` | 本地空库导入 |
| `/api/v1/admin/*` | MFA 管理员运营接口 |

数据结构见架构文档；环境参数、构建、备份与恢复见打包和迁移文档。

## 8. SQL 访问、初始化与配置如何接起来

`ReleaseApplication` 扫描发行服务与共享 RP 包并启用定时任务。Spring 根据 profile 选择 JDBC 驱动和连接地址；`RuntimeMode` 根据 `story.mode` 决定条件 Bean，故 profile 和 mode 必须一致。商业类集中于 `release/cloud/`，离线 Maven profile 在编译和资源打包时排除它们；仅切换运行参数不能替代构建隔离。

`Migrations` 是本项目的自定义初始化器，不是 Flyway。它创建 `schema_version`、选择 PostgreSQL/SQLite SQL、计算 checksum，并在首次使用时事务执行。SQL 以显式 `--statement--` 分隔，避免破坏 SQLite 触发器。已有版本 checksum 不一致时拒绝启动，不会自动迁移已经修改的 V1。新增数据库结构需要设计新的升级步骤，不可直接修改已投产 V1。

业务类通过 `JdbcTemplate` 使用参数化 SQL，共享 RP 层负责世界状态和回合语义。数据表关联由领域代码维护，并非每个字段都有数据库外键。`ObjectStore`、`IdentityService`、`ModelConnections` 是双版本替换点：同一个业务 API 在商业版连接 S3/账号/平台模型，在离线版连接本地文件/本机身份/本地模型。

YAML 里的 `${STORY_...:默认值}` 从环境变量取配置。开发入口只为独立本地环境生成管理员、数据库和对象凭据；生产 Compose 读取私有 `.env`。客户端不应获得 PostgreSQL、S3 或平台模型密钥。密钥未配置导致的启动错误应修复配置，不应把校验删除。
