#!/bin/bash
# 停止 MLX-Audio TTS 服务

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
PID_FILE="$PROJECT_ROOT/unified-logs/pids/pids.txt"

if [ ! -f "$PID_FILE" ]; then
    echo "MLX-Audio TTS 服务未运行 (PID 文件不存在)"
    exit 0
fi

PID=$(awk '$1=="mlx-audio"{print $2}' "$PID_FILE" | tail -1)
if [ -z "$PID" ]; then
    echo "MLX-Audio TTS 服务未运行 (PID 未记录)"
    exit 0
fi
if kill -0 "$PID" 2>/dev/null; then
    echo "停止 MLX-Audio TTS 服务 (PID=$PID)..."
    kill "$PID"
    sleep 2
    # 强制杀死
    if kill -0 "$PID" 2>/dev/null; then
        echo "强制杀死进程..."
        kill -9 "$PID" 2>/dev/null || true
    fi
    echo "MLX-Audio TTS 服务已停止"
else
    echo "进程 $PID 已不存在"
fi
grep -v "^mlx-audio " "$PID_FILE" > "$PID_FILE.tmp" || true
mv "$PID_FILE.tmp" "$PID_FILE"