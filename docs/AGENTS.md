# Agent 开发指引

本文件给 Codex / Cursor / Claude Code 等 Agent 使用，记录最常用且仍有效的项目入口。

## 本机服务

MySQL 与 Redis 连接信息统一维护在 `config/local-services.env`，并应与 `backend/src/main/resources/application-local.yml` 保持一致。

优先使用项目脚本：

```bash
./scripts/local-db.sh status
./scripts/local-db.sh mysql -e "SELECT id, name, role_code FROM role_card;"
./scripts/local-db.sh redis ping
./scripts/local-db.sh redis HLEN rag:chunks:embeds
```

不要假设 `mysql` 或 `redis-cli` 已在 PATH 中。

## 开发启动

```bash
./startup-scripts/start-all.sh
./startup-scripts/stop-all.sh
```

启动脚本会拉起后端、前端、Electron、ASR，并检查 Win Astra TTS；SearXNG 在开发期通过 Docker 启动。不要使用按模块启动脚本，当前只维护一键入口。

## 重要配置

- `config/runtime-config.json`：运行时 LLM / Embedding / TTS / Memos / 客户端配置。
- `config/mcp-servers.json`：MCP 服务器注册表。
- `backend/src/main/resources/application-local.yml`：开发期后端默认配置。
- `backend/src/main/resources/application-packaged.yml`：打包版后端默认配置。
- `client/electron/service-manager.js`：打包版内置服务编排。
- `docs/scripts.md`：所有仍保留脚本的用途说明。

## 文档维护规则

- 当前状态写入 `README.md`、`docs/项目规范.md`、`docs/packaging.md`、`docs/scripts.md`。
- 待办只写 `docs/TODO.md`。
- Cursor 长期事实只写 `.cursor/memory/MEMORY.md`。
- 大功能开工前才在 `.cursor/plans/` 新增 plan，完成后并入正式文档并删除 plan。
