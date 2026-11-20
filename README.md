# 幕间 · Story Space

角色扮演与连续故事桌面应用，提供商业版和离线版。商业版分为用户端与管理端，离线版作为一个整体运行和发行。角色库、篇章、场景设定、分支、回退、收藏、图片归档与语音输入输出共用界面。商业版目前采用免费邀请测试，不含支付功能。

## 两个版本

| 项目 | 商业版 | 离线版 |
| --- | --- | --- |
| 桌面包 | Electron + Vue 静态界面 | 界面 + Java + llama.cpp + ONNX 语音运行时 |
| 账号 | 邀请码、邮箱验证、账号登录 | 本机身份，无账号服务器 |
| 数据 | PostgreSQL/pgvector + S3 对象存储 | SQLite WAL/FTS5 + 本地文件 |
| 聊天模型 | 平台模型或用户自带公开 HTTPS API | 单独导入签名模型包，或显式配置自己的模型 |
| 运营 | 独立管理站点，管理员 MFA | 不含账号与运营代码 |

当前实现位于 `apps/`，从旧数据库迁移的数据通过独立导出工具导入。源码与模型许可边界见 [打包说明](docs/packaging.md)。

## 开发

Node 24、JDK 17+；Maven 使用仓库自带 wrapper。

```bash
npm run dev:offline             # 离线版前后端（npm run dev 等价）
npm run dev:commercial          # 商业版用户端、管理端、后端及测试基础服务
npm run dev:admin               # 同一商业环境，以管理端网页为入口

npm run package:commercial:user # 商业版用户端完整打包
npm run package:commercial:admin # 商业版管理端与共用后端完整部署包
npm run package:offline         # 离线版整体完整打包
```

开发入口自动安装缺失的前端依赖并构建后端；商业版开发需要 Docker 已启动。离线版无需先准备原生运行时即可启动界面与 API，实际对话需签名模型包或可用的自有本地模型。完整离线打包会组装原生运行时，构建工具前提见 [启动、打包与部署](docs/packaging.md)。开发数据与安装版隔离，保存在 `.local/dev/`。

`npm run setup` 用于安装/更新依赖；`npm run build` 与 `npm test` 用于完整构建和回归。旧 `dev:cloud` / `package:cloud` 是商业版命令别名。

## 目录

```text
apps/desktop/     Electron、Vue、三种界面构建、唯一前端依赖锁
apps/server/      独立 Spring 服务、数据库迁移、ONNX 语音源码
packages/rp/      共享 RP 状态、解析、分支与回退
external/        本机参考工程，不参与发行构建
scripts/         当前构建、迁移、发行和验证工具
packaging/       发行许可、图标、运行时组装与签名模型包
deployment/     云部署配置
tools/maven/    Maven wrapper
docs/           当前架构、实现、操作与排障说明
```

私人角色素材位于 `.local/personal/`，构建与验收产物位于 `artifacts/`。体验记录和截图保存在本机 Git 排除目录。

## 文档入口

- [新旧技术栈对照](docs/technology-stack.md)：从 MySQL、Redis 理解 PostgreSQL、SQLite、向量检索、本地模型与完整请求链路。
- [项目架构](docs/architecture.md)：模块职责、双版本边界与数据模型。
- [技术实现](docs/implementation.md)：聊天、记忆、认证、语音与 API。
- [打包与部署](docs/packaging.md)：安装包、开源源码与环境变量。
- [数据迁移](docs/migration.md)：合并、验证、备份与恢复。
- [开发规范](docs/项目规范.md)、[脚本说明](docs/scripts.md)、[踩坑与排障](docs/troubleshooting.md)。
- [待办与商业化建议](docs/TODO.md)：未完成工作集中维护于此。

当前已验证 macOS arm64 的本地运行、原生对话和语音，以及独立 PostgreSQL 上的身份隔离与模拟流并发。硬件、公开发行和外部服务的未完成验证见 TODO；本机通过不能代替生产验收。
