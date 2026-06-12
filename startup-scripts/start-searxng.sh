#!/bin/bash

# 启动本地 SearXNG（Docker），作为联网搜索后端，监听 http://localhost:8888
# 依赖：本机已安装并运行 Docker Desktop / Docker Engine

set -e

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
COMPOSE_FILE="$PROJECT_ROOT/services/searxng/docker-compose.yml"
SEARXNG_URL="http://localhost:8888"

echo "🔎 Starting SearXNG via Docker..."

if ! command -v docker >/dev/null 2>&1; then
    echo "❌ Docker not found. Please install Docker Desktop first."
    exit 1
fi

docker compose -f "$COMPOSE_FILE" up -d

echo "⏳ Waiting for SearXNG to be ready at $SEARXNG_URL ..."
for i in $(seq 1 20); do
    if curl -s --max-time 5 "$SEARXNG_URL/search?q=test&format=json" | grep -q '"results"'; then
        echo "✅ SearXNG is running at $SEARXNG_URL (JSON API OK)"
        exit 0
    fi
    sleep 3
done

echo "⚠️  SearXNG started but JSON API not confirmed yet. Check: docker logs searxng"
echo "   Try manually: curl '$SEARXNG_URL/search?q=test&format=json'"
