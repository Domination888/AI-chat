#!/bin/bash

# AI-Chat 完整开发环境停止脚本

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PID_DIR="$PROJECT_ROOT/unified-logs/pids"
PID_FILE="$PID_DIR/pids.txt"

echo "🛑 Stopping AI-Chat Development Environment..."

# 1) 优先用 TTS 自带的停止脚本
TTS_STOP="$PROJECT_ROOT/services/gpt-sovits/start.sh"
if [ -f "$TTS_STOP" ]; then
    echo "🔊 Stopping TTS via start.sh stop..."
    bash "$TTS_STOP" stop 2>/dev/null || true
fi

# 2) 从 PID 文件杀进程
if [ -f "$PID_FILE" ]; then
    echo "🔍 Killing processes from PID file..."
    awk '{print $2}' "$PID_FILE" | xargs kill 2>/dev/null || true
    rm -f "$PID_FILE"
    # 等待进程优雅退出
    sleep 2
fi

# 3) 按端口兜底杀进程
# 停止 MLX-Audio TTS
MLX_TTS_STOP="$PROJECT_ROOT/services/mlx-audio-tts/stop.sh"
if [ -f "$MLX_TTS_STOP" ]; then
    echo "Stopping MLX-Audio TTS..."
    bash "$MLX_TTS_STOP" 2>/dev/null || true
fi

ports=(8080 3000 9000 9880 9881)
for port in "${ports[@]}"; do
    if lsof -ti:$port >/dev/null 2>&1; then
        echo "🔍 Killing processes on port $port..."
        lsof -ti:$port | xargs kill 2>/dev/null || true
    fi
done

echo "✅ AI-Chat Development Environment has been stopped."