# 工具入口

## 日常开发与打包

以下命令均在仓库根目录执行。版本只有商业版与离线版；商业版用户端和管理端共用后端。

| 命令 | 实际作用 |
| --- | --- |
| `npm run setup` | 安装/更新桌面唯一依赖树，解析服务端依赖 |
| `npm run dev` / `npm run dev:offline` | 自动构建最新离线前后端并启动桌面；无需预先 staging JRE |
| `npm run dev:commercial` | 启动专用开发 Docker 基础服务、商业后端、用户端；输出管理端与开发邮箱地址 |
| `npm run dev:admin` | 同一商业开发环境，以管理端网页为入口 |
| `npm run dev:otp` | 读取本地开发管理员 TOTP 密钥，输出当前动态验证码 |
| `npm run dev:offline -- --smoke` | 自动验证离线前后端与认证角色 API，完成后停止 |
| `npm run dev:commercial -- --smoke` | 自动验证商业前后端、MFA 与管理 API，完成后停止 |
| `npm run package:commercial:user` | 自动构建并打包商业版用户端 |
| `npm run package:commercial:admin` | 自动构建管理端网页、商业 JAR、独立 Docker 部署包 |
| `npm run package:offline` | 自动构建前后端、组装/刷新原生运行时，整体打包离线版 |
| `npm run build` | 前端检查、三种界面、商业 JAR、离线 JAR 的全量构建 |
| `npm test` | 前端协议/模型包与离线 Java 回归 |
| `npm run test:workflow` | 开发工作流辅助逻辑回归 |

`dev:cloud` / `package:cloud` 保留为商业版兼容别名。`scripts/workspace.cjs` 是完整启动与打包的统一编排入口。商业版开发需 Docker 已启动，离线原生打包的工具前提见 [packaging](packaging.md)。Ctrl+C 停止本次启动内容并保留专用开发数据；`.local/dev/` 和开发 Docker volumes 不与安装版/生产数据混用。

## 发行工具

- `scripts/package-all.sh offline|commercial|admin [mac|win]`、`scripts/release/build.sh`：兼容入口，转发到完整流程。
- `prepare-runtime.py <平台>`：校验离线 JAR、刷新现有 staging；缺少 staging 时使用本机原生工具自动组装。
- `stage-runtime.py <平台> --java ... --llama ... --voice ... --ffmpeg ...`：指定路径组装离线运行时，拒绝商业 JAR；macOS 处理动态库路径。
- `stage-ci.py`：CI 的原生运行时组装入口。
- `fetch-models.py`：发行方下载模型并记录来源；客户端不执行此下载器。
- `sign-model-pack.py`：生成模型清单、SHA-256 与 Ed25519 签名。
- `export-source.py`：白名单导出 Apache-2.0 离线源码，剔除商业类、个人配置、私有素材与权重。
- `verify-artifacts.cjs`：检查桌面包体和源码边界，生成产物校验清单。
- `init-cloud.py`：写入权限 600 的私有生产部署 `.env`，不输出凭据。
- `.github/workflows/release-candidates.yml`：手动构建 macOS/Windows 候选，不自动发布。

管理端部署包自带 `SHA256SUMS` 和 [部署说明](commercial-admin-deploy.md)。它与商业版用户端连接同一商业服务；不是另一套用户数据库。

## 数据与验收

- `export-legacy.py <输出.json> --user-id <ID...>`：MySQL 只读事务导出；`--all-roles` 保留个人库中未使用角色，`--memories` 附带记忆审阅副本。
- `merge-exports.py <输出.json> <输入...>`：合并可移植导出，重映射角色、消息、回合、世界、附件标识并保留引用。
- `native-smoke.cjs`：隔离目录中调用真实本地模型、ASR/TTS；默认 30 轮，`STORY_EVAL_TURNS` 可调整。
- `scripts/tests/`：合并工具与开发辅助逻辑回归。

角色语料工具使用 `data/` 与 `.local/personal/personas/`。体验记录、截图和审计快照保存在本机。

## 黍完整流程验收

- `scripts/release/stage-shu.py`：复制已有私人黍素材、角色卡/lore、技能与安全 builtin 工具示例，供显式个人构建使用。
- `scripts/release/verify-shu-flow.cjs`：实际 SQLite/PostgreSQL、角色导入、旧技能/MCP、模型工具调用、语义兴趣、聊天、ASR、Astra WAV、日历、记忆纠正、日报主动 SSE 与反馈。只写 `.local/dev/verification/flow-*` 和专用测试 PostgreSQL；需要该目录已有私有 services.json（测试模型地址/令牌）、story-offline.jar、story-cloud.jar 和名为 story-space-migration-test-pg 的专用容器。ASR/Astra/SearxNG 使用显式环境地址或文中默认值。
- 真实流程报告记录核心通过与完整能力通过两个字段；外部搜索无来源不会冒充全功能通过。`--serve` 保留本次后端供桌面验收，结束后 SIGINT 关闭本次子进程。不会自动连接或修改安装版数据。

`verify-shu-flow.cjs --voice-only` 单独验证两版后端的真实 Astra 极短句与合并句，适用于语音修复回归，不依赖当天日报已发布。
