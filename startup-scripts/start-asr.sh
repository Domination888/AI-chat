#!/bin/bash

# 启动ASR服务

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOG_DIR="$PROJECT_ROOT/unified-logs"
PID_DIR="$LOG_DIR/pids"
PID_FILE="$PID_DIR/pids.txt"
ASR_PORT=9000

mkdir -p "$LOG_DIR/asr" "$PID_DIR"

# 检查端口并清理
if lsof -ti:$ASR_PORT >/dev/null; then
    echo "⚠️  Port $ASR_PORT is in use, killing existing processes..."
    lsof -ti:$ASR_PORT | xargs kill -9 2>/dev/null || true
    sleep 2
fi

echo "🎤 Starting SenseVoice ASR service..."
cd "$PROJECT_ROOT/services/sense-voice"
python server.py > "$LOG_DIR/asr/app.log" 2>&1 &
cd "$PROJECT_ROOT"

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

# 抓真实监听 PID（python 一般 $! 即真身，但仍以端口为准更稳）
ASR_PID=$(lsof -nP -iTCP:$ASR_PORT -sTCP:LISTEN -t 2>/dev/null | sort -n | head -1)

# 保存PID（单一 pids.txt）
if [ -f "$PID_FILE" ]; then
    grep -v "^asr " "$PID_FILE" > "$PID_FILE.tmp" || true
    mv "$PID_FILE.tmp" "$PID_FILE"
fi
[ -n "$ASR_PID" ] && echo "asr $ASR_PID" >> "$PID_FILE"