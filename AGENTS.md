# Agent 开发指引

## 本机服务连接

MySQL 与 Redis 连接信息统一维护在 [`config/local-services.env`](config/local-services.env)，与 [`backend/src/main/resources/application-local.yml`](backend/src/main/resources/application-local.yml) 一致。

### 推荐入口（Codex / Cursor / Claude Code 等）

```bash
./scripts/local-db.sh status
./scripts/local-db.sh mysql -e "SELECT id, name, role_code FROM role_card;"
./scripts/local-db.sh redis ping
```

### 连接参数速查

| 服务 | Host | Port | 库/说明 |
|------|------|------|---------|
| MySQL | localhost | 3306 | 数据库 `ai_chat`，用户 `root` |
| Redis | localhost | 6379 | 无密码，DB 0 |

### CLI 路径（macOS）

- MySQL: `/usr/local/mysql-8.4.5-macos15-arm64/bin/mysql`
- Redis: `/opt/homebrew/bin/redis-cli`

若终端报 `mysql: command not found`，请用 `./scripts/local-db.sh`，或确认 `~/.zshrc` 已加入 MySQL `bin` 目录。

### 初始化数据库

```bash
./scripts/local-db.sh mysql < backend/init.sql
# 或
/usr/local/mysql-8.4.5-macos15-arm64/bin/mysql -uroot -p ai_chat < backend/init.sql
```
