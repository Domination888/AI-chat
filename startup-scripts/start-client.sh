#!/bin/bash

# 启动Electron客户端

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOG_DIR="$PROJECT_ROOT/unified-logs"
PID_DIR="$LOG_DIR/pids"
PID_FILE="$PID_DIR/pids.txt"

mkdir -p "$PID_DIR"

echo "🖥️  Starting Electron client..."
cd "$PROJECT_ROOT/client"
if [ "${CLIENT_LOG:-}" = "1" ]; then
	mkdir -p "$LOG_DIR/client"
	npx electron . > "$LOG_DIR/client/app.log" 2>&1 &
else
	npx electron . >/dev/null 2>&1 &
fi
CLIENT_PID=$!
cd "$PROJECT_ROOT"

# 保存PID（单一 pids.txt）
if [ -f "$PID_FILE" ]; then
	grep -v "^client " "$PID_FILE" > "$PID_FILE.tmp" || true
	mv "$PID_FILE.tmp" "$PID_FILE"
fi
echo "client $CLIENT_PID" >> "$PID_FILE"

echo "✅ Electron client is starting..."
if [ "${CLIENT_LOG:-}" = "1" ]; then
	echo "📊 Logs: $LOG_DIR/client/app.log"
fi

# 等待几秒钟确保启动
sleep 3