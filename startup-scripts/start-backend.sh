#!/bin/bash

# 启动后端服务

LOG_DIR="unified-logs"
PID_DIR="unified-logs/pids"
BACKEND_PORT=8080

mkdir -p $LOG_DIR/backend $PID_DIR

# 检查端口并清理
if lsof -ti:$BACKEND_PORT >/dev/null; then
    echo "⚠️  Port $BACKEND_PORT is in use, killing existing processes..."
    lsof -ti:$BACKEND_PORT | xargs kill -9 2>/dev/null || true
    sleep 2
fi

echo "📦 Starting Spring Boot backend..."
cd backend
./mvnw spring-boot:run > ../$LOG_DIR/backend/app.log 2>&1 &
BACKEND_PID=$!
cd ..

# 保存PID
echo $BACKEND_PID > $PID_DIR/backend.pid
echo $BACKEND_PID >> $PID_DIR/all_pids.txt

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