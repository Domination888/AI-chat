---
description: 本机 MySQL / Redis 连接方式，供 Agent 直接查询或排障
alwaysApply: true
---

# 本机 MySQL / Redis

本项目后端使用 MySQL（`ai_chat`）和 Redis（`localhost:6379`）。连接信息见 `config/local-services.env`。

## 优先使用项目脚本（不依赖 PATH）

```bash
./scripts/local-db.sh status
./scripts/local-db.sh mysql -e "SELECT id, name, role_code FROM role_card;"
./scripts/local-db.sh redis ping
./scripts/local-db.sh redis KEYS 'rag:*'
```

## 直连命令（PATH 已配置时）

```bash
mysql -h localhost -P 3306 -uroot -p<password> ai_chat -e "SHOW TABLES;"
redis-cli -h localhost -p 6379 ping
```

密码与 JDBC URL 以 `config/local-services.env` 和 `backend/src/main/resources/application-local.yml` 为准。

## 注意

- `mysql` 客户端路径：`/usr/local/mysql-8.4.5-macos15-arm64/bin/mysql`（若 `mysql` 命令找不到，用脚本或完整路径）
- `redis-cli` 路径：`/opt/homebrew/bin/redis-cli`
- 排障 RAG 缓存：`./scripts/local-db.sh redis HLEN rag:chunks:embeds`
