#!/bin/bash

# 启动ASR服务

LOG_DIR="unified-logs"
PID_DIR="unified-logs/pids"
ASR_PORT=9000

mkdir -p $LOG_DIR/asr $PID_DIR

# 检查端口并清理
if lsof -ti:$ASR_PORT >/dev/null; then
    echo "⚠️  Port $ASR_PORT is in use, killing existing processes..."
    lsof -ti:$ASR_PORT | xargs kill -9 2>/dev/null || true
    sleep 2
fi

echo "🎤 Starting SenseVoice ASR service..."
cd services/sense-voice
python server.py > ../../$LOG_DIR/asr/app.log 2>&1 &
ASR_PID=$!
cd ../..

# 保存PID
echo $ASR_PID > $PID_DIR/asr.pid
echo $ASR_PID >> $PID_DIR/all_pids.txt

# 等待启动
echo "⏳ Waiting for ASR service to start..."
sleep 10

# 检查是否启动成功
if curl -s --max-time 5 http://localhost:$ASR_PORT/healthz > /dev/null; then
    echo "✅ ASR service is running at http://localhost:$ASR_PORT"
    echo "📊 Logs: $LOG_DIR/asr/app.log"
else
    echo "❌ ASR service failed to start. Check $LOG_DIR/asr/app.log"
    exit 1
fi