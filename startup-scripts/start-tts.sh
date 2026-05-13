#!/bin/bash

# 启动TTS服务

LOG_DIR="unified-logs"
PID_DIR="unified-logs/pids"
TTS_PORT=9880

mkdir -p $LOG_DIR/tts $PID_DIR

# 检查端口并清理
if lsof -ti:$TTS_PORT >/dev/null; then
    echo "⚠️  Port $TTS_PORT is in use, killing existing processes..."
    lsof -ti:$TTS_PORT | xargs kill -9 2>/dev/null || true
    sleep 2
fi

echo "🔊 Starting GPT-SoVITS TTS service..."
cd services/gpt-sovits

# 检查TTS启动脚本是否存在
if [ -f "start.sh" ]; then
    bash start.sh > ../../$LOG_DIR/tts/app.log 2>&1 &
    TTS_PID=$!
    cd ../..
    
    # 保存PID
    echo $TTS_PID > $PID_DIR/tts.pid
    echo $TTS_PID >> $PID_DIR/all_pids.txt
    
    # 等待启动
    echo "⏳ Waiting for TTS service to start..."
    sleep 10
    
    # 检查端口是否被占用（更可靠的检查方式）
    if lsof -ti:$TTS_PORT >/dev/null; then
        echo "✅ TTS service is running on port $TTS_PORT"
        echo "📊 Logs: $LOG_DIR/tts/app.log"
    else
        echo "❌ TTS service failed to start. Check $LOG_DIR/tts/app.log"
        exit 1
    fi
else
    echo "⚠️  TTS start script not found at services/gpt-sovits/start.sh"
    echo "Please check if TTS service is properly configured."
    exit 1
fi