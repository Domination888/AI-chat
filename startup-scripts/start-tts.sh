#!/bin/bash

# 启动 TTS 服务（支持引擎切换）
# 用法:
#   bash start-tts.sh                    # 默认启动 GPT-SoVITS
#   bash start-tts.sh --engine mlx-audio # 启动 MLX-Audio + Qwen3-TTS
#   bash start-tts.sh --engine gpt-sovits # 启动 GPT-SoVITS

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

ENGINE="gpt-sovits"

while [[ $# -gt 0 ]]; do
    case $1 in
        --engine) ENGINE="$2"; shift 2 ;;
        --no-restart) NO_RESTART="--no-restart"; shift ;;
        *) echo "未知参数: $1"; exit 1 ;;
    esac
done

case "$ENGINE" in
    mlx-audio)
        TTS_START="$PROJECT_ROOT/services/mlx-audio-tts/start.sh"
        if [ ! -f "$TTS_START" ]; then
            echo "MLX-Audio TTS 启动脚本不存在: $TTS_START"
            echo "请确认 services/mlx-audio-tts/ 目录已就绪"
            exit 1
        fi
        echo "Starting MLX-Audio TTS service (Qwen3-TTS, port 9881)..."
        bash "$TTS_START" ${NO_RESTART:-}
        ;;
    gpt-sovits)
        TTS_START="$PROJECT_ROOT/services/gpt-sovits/start.sh"
        if [ ! -f "$TTS_START" ]; then
            echo "GPT-SoVITS TTS 启动脚本不存在: $TTS_START"
            echo "请确认 services/gpt-sovits/ 目录已就绪"
            exit 1
        fi
        echo "Starting GPT-SoVITS TTS service (port 9880)..."
        bash "$TTS_START" ${NO_RESTART:-}
        ;;
    *)
        echo "未知 TTS 引擎: $ENGINE"
        echo "可选: mlx-audio, gpt-sovits"
        exit 1
        ;;
esac