#!/bin/bash

# GPT-SoVITS TTS服务启动脚本

# 设置工作目录
cd "$(dirname "$0")"

# 进入GPT-SoVITS目录
cd GPT-SoVITS

# 检查是否使用内置的runtime环境
if [ -f "runtime/bin/python3.10" ]; then
    PYTHON_BIN="runtime/bin/python3.10"
    echo "🐍 Using built-in Python runtime"
else
    PYTHON_BIN="python"
    echo "🐍 Using system Python"
fi

echo "🚀 Starting GPT-SoVITS TTS service..."

# 使用api_v2.py启动服务
exec $PYTHON_BIN api_v2.py -a 127.0.0.1 -p 9880 -c GPT_SoVITS/configs/tts_infer.yaml