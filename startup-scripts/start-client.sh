#!/bin/bash

# 启动Electron客户端

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOG_DIR="$PROJECT_ROOT/unified-logs"
PID_DIR="$LOG_DIR/pids"
PID_FILE="$PID_DIR/pids.txt"

mkdir -p "$LOG_DIR/client" "$PID_DIR"

echo "🖥️  Starting Electron client..."
cd "$PROJECT_ROOT/client"
npx electron . > "$LOG_DIR/client/app.log" 2>&1 &
CLIENT_PID=$!
cd "$PROJECT_ROOT"

# 保存PID（单一 pids.txt）
if [ -f "$PID_FILE" ]; then
	grep -v "^electron " "$PID_FILE" > "$PID_FILE.tmp" || true
	mv "$PID_FILE.tmp" "$PID_FILE"
fi
echo "electron $CLIENT_PID" >> "$PID_FILE"

echo "✅ Electron client is starting..."
echo "📊 Logs: $LOG_DIR/client/app.log"

# 等待几秒钟确保启动
sleep 3