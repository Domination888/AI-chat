#!/bin/bash

# TTS 服务已迁移到 Win (Astra/Genie-TTS :5000)
# Mac 端无需启动本地 TTS 服务，后端直接调用 Win API
# 此脚本仅做健康检查

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

TTS_URL="http://192.168.124.2:5000/api/tts/status"

echo "Checking Astra TTS service on Win ($TTS_URL)..."
if curl -s --max-time 5 "$TTS_URL" > /dev/null 2>&1; then
    echo "Astra TTS service is reachable."
else
    echo "WARNING: Astra TTS service ($TTS_URL) is not reachable."
    echo "Make sure the TTS service is running on Win (192.168.124.2:5000)."
fi