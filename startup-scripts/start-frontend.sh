#!/bin/bash

# 启动前端服务

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOG_DIR="$PROJECT_ROOT/unified-logs"
PID_DIR="$LOG_DIR/pids"
PID_FILE="$PID_DIR/pids.txt"
FRONTEND_PORT=3000

mkdir -p "$LOG_DIR/frontend" "$PID_DIR"

# 检查端口并清理
if lsof -ti:$FRONTEND_PORT >/dev/null; then
    echo "⚠️  Port $FRONTEND_PORT is in use, killing existing processes..."
    lsof -ti:$FRONTEND_PORT | xargs kill -9 2>/dev/null || true
    sleep 2
fi

echo "🎨 Starting Vite frontend server..."
cd "$PROJECT_ROOT/client/src"
npm run dev > "$LOG_DIR/frontend/app.log" 2>&1 &
FRONTEND_PID=$!
cd "$PROJECT_ROOT"

# 保存PID（单一 pids.txt）
if [ -f "$PID_FILE" ]; then
    grep -v "^frontend " "$PID_FILE" > "$PID_FILE.tmp" || true
    mv "$PID_FILE.tmp" "$PID_FILE"
fi
echo "frontend $FRONTEND_PID" >> "$PID_FILE"

# 等待启动
echo "⏳ Waiting for frontend to start..."
sleep 5

# 检查是否启动成功
if curl -s --max-time 5 http://localhost:$FRONTEND_PORT > /dev/null; then
    echo "✅ Frontend is running at http://localhost:$FRONTEND_PORT"
    echo "📊 Logs: $LOG_DIR/frontend/app.log"
else
    echo "❌ Frontend failed to start. Check $LOG_DIR/frontend/app.log"
    exit 1
fi