#!/bin/bash

# 启动后端服务

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOG_DIR="$PROJECT_ROOT/unified-logs"
PID_DIR="$LOG_DIR/pids"
PID_FILE="$PID_DIR/pids.txt"
BACKEND_PORT=8080

mkdir -p "$LOG_DIR/backend" "$PID_DIR"

# 检查端口并清理
if lsof -ti:$BACKEND_PORT >/dev/null; then
    echo "⚠️  Port $BACKEND_PORT is in use, killing existing processes..."
    lsof -ti:$BACKEND_PORT | xargs kill -9 2>/dev/null || true
    sleep 2
fi

echo "📦 Starting Spring Boot backend..."
cd "$PROJECT_ROOT/backend"
./mvnw spring-boot:run > "$LOG_DIR/backend/app.log" 2>&1 &
BACKEND_PID=$!
cd "$PROJECT_ROOT"

# 保存PID（单一 pids.txt）
if [ -f "$PID_FILE" ]; then
    grep -v "^backend " "$PID_FILE" > "$PID_FILE.tmp" || true
    mv "$PID_FILE.tmp" "$PID_FILE"
fi
echo "backend $BACKEND_PID" >> "$PID_FILE"

# 等待启动
echo "⏳ Waiting for backend to start..."
sleep 15

# 检查是否启动成功
if curl -s --max-time 5 http://localhost:$BACKEND_PORT/api/health > /dev/null; then
    echo "✅ Backend is running at http://localhost:$BACKEND_PORT"
    echo "📊 Logs: $LOG_DIR/backend/app.log"
else
    echo "❌ Backend failed to start. Check $LOG_DIR/backend/app.log"
    exit 1
fi