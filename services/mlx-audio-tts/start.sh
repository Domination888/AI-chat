#!/bin/bash
# ============================================================
# MLX-Audio TTS 服务启动脚本
# 部署约束：Mac M4 32GB，Apple MLX 原生加速
# 端口：9881（与 GPT-SoVITS 9880 互斥）
# ============================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
VENV_DIR="$SCRIPT_DIR/.venv"
PID_DIR="$PROJECT_ROOT/unified-logs/pids"
LOG_DIR="$PROJECT_ROOT/unified-logs/tts/mlx-audio"

# 默认配置
HOST="127.0.0.1"
PORT="9881"
MODEL="mlx-community/Qwen3-TTS-12Hz-0.6B-Base-bf16"

# 解析参数
while [[ $# -gt 0 ]]; do
    case $1 in
        --host)   HOST="$2";   shift 2 ;;
        --port)   PORT="$2";   shift 2 ;;
        --model)  MODEL="$2";  shift 2 ;;
        --no-restart) NO_RESTART=1; shift ;;
        *) echo "未知参数: $1"; exit 1 ;;
    esac
done

# 创建目录
mkdir -p "$PID_DIR" "$LOG_DIR"

PID_FILE="$PID_DIR/pids.txt"
LOG_FILE="$LOG_DIR/server.log"

get_pid_from_file() {
    if [ -f "$PID_FILE" ]; then
        awk '$1=="mlx-audio"{print $2}' "$PID_FILE" | tail -1
    fi
}

write_pid() {
    if [ -f "$PID_FILE" ]; then
        grep -v "^mlx-audio " "$PID_FILE" > "$PID_FILE.tmp" || true
        mv "$PID_FILE.tmp" "$PID_FILE"
    fi
    echo "mlx-audio $1" >> "$PID_FILE"
}

remove_pid_entry() {
    if [ -f "$PID_FILE" ]; then
        grep -v "^mlx-audio " "$PID_FILE" > "$PID_FILE.tmp" || true
        mv "$PID_FILE.tmp" "$PID_FILE"
    fi
}

# 若端口已被占用（可能是旧版 mlx_audio.server），先释放
PORT_PID=$(lsof -i :"$PORT" -sTCP:LISTEN -t 2>/dev/null | head -1 || true)
if [ -n "$PORT_PID" ]; then
    FILE_PID="$(get_pid_from_file)"
    if [ -n "$FILE_PID" ] && [ "$FILE_PID" = "$PORT_PID" ] && [ "${NO_RESTART:-0}" = "1" ]; then
        echo "MLX-Audio TTS 已在运行 (PID=$PORT_PID)，跳过启动"
        exit 0
    fi
    echo "释放端口 $PORT (PID=$PORT_PID)..."
    kill "$PORT_PID" 2>/dev/null || true
    sleep 2
fi

# 检查是否已在运行
OLD_PID="$(get_pid_from_file)"
if [ -n "$OLD_PID" ] && kill -0 "$OLD_PID" 2>/dev/null; then
    if [ "${NO_RESTART:-0}" = "1" ]; then
        echo "MLX-Audio TTS 已在运行 (PID=$OLD_PID)，跳过启动"
        exit 0
    fi
    echo "停止旧进程 (PID=$OLD_PID)..."
    kill "$OLD_PID" 2>/dev/null || true
    sleep 2
fi
remove_pid_entry

# 检查虚拟环境
if [ ! -d "$VENV_DIR" ]; then
    echo "虚拟环境不存在，请先运行: cd $SCRIPT_DIR && python3 -m venv .venv && source .venv/bin/activate && pip install -r requirements.txt"
    exit 1
fi

# 激活虚拟环境
source "$VENV_DIR/bin/activate"

echo "============================================================"
echo "  MLX-Audio TTS 服务启动"
echo "  模型: $MODEL"
echo "  地址: http://$HOST:$PORT"
echo "  日志: $LOG_FILE"
echo "============================================================"

# 启动服务（后台运行）
cd "$SCRIPT_DIR"
export PYTHONUNBUFFERED=1
nohup python server_entry.py \
    --host "$HOST" \
    --port "$PORT" \
    > "$LOG_FILE" 2>&1 &

SERVER_PID=$!
write_pid "$SERVER_PID"

# 等待服务启动
echo "等待服务就绪..."
for i in $(seq 1 30); do
    if curl -s "http://$HOST:$PORT/v1/models" > /dev/null 2>&1; then
        echo "MLX-Audio TTS 服务已启动 (PID=$SERVER_PID)"
        echo "  API: http://$HOST:$PORT/v1/audio/speech"
        exit 0
    fi
    sleep 1
done

echo "警告: 服务启动超时，请检查日志: $LOG_FILE"
echo "  PID=$SERVER_PID 可能仍在初始化中"
exit 0