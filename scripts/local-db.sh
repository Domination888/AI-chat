#!/usr/bin/env bash
# 本机 MySQL / Redis 快捷入口（Codex、Cursor、Claude Code 等可直接调用）
#
# 用法:
#   ./scripts/local-db.sh status
#   ./scripts/local-db.sh mysql -e "SELECT id, name FROM role_card;"
#   ./scripts/local-db.sh redis ping
#   ./scripts/local-db.sh redis KEYS 'rag:*'

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=/dev/null
source "$ROOT/config/local-services.env"

usage() {
  cat <<EOF
用法: $0 <mysql|redis|status> [参数...]

  mysql   连接 ai_chat 数据库（参数传给 mysql 客户端）
  redis   连接本机 Redis（参数传给 redis-cli）
  status  检查 MySQL / Redis 是否可达

示例:
  $0 status
  $0 mysql -e "SHOW TABLES;"
  $0 redis ping
  $0 redis HLEN rag:chunks:embeds
EOF
}

cmd_status() {
  local ok=0
  if "$MYSQL_BIN" -h"$MYSQL_HOST" -P"$MYSQL_PORT" -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" -e "SELECT 1" "$MYSQL_DATABASE" >/dev/null 2>&1; then
    echo "MySQL: OK ($MYSQL_HOST:$MYSQL_PORT/$MYSQL_DATABASE)"
  else
    echo "MySQL: FAIL ($MYSQL_HOST:$MYSQL_PORT/$MYSQL_DATABASE)"
    ok=1
  fi
  if "$REDIS_CLI" -h "$REDIS_HOST" -p "$REDIS_PORT" ping >/dev/null 2>&1; then
    echo "Redis: OK ($REDIS_HOST:$REDIS_PORT)"
  else
    echo "Redis: FAIL ($REDIS_HOST:$REDIS_PORT)"
    ok=1
  fi
  return $ok
}

case "${1:-}" in
  mysql)
    shift
    exec "$MYSQL_BIN" \
      -h"$MYSQL_HOST" -P"$MYSQL_PORT" \
      -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" \
      "$MYSQL_DATABASE" "$@"
    ;;
  redis)
    shift
    exec "$REDIS_CLI" -h "$REDIS_HOST" -p "$REDIS_PORT" "$@"
    ;;
  status)
    cmd_status
    ;;
  -h|--help|help|"")
    usage
    exit 0
    ;;
  *)
    echo "未知子命令: $1" >&2
    usage >&2
    exit 1
    ;;
esac
