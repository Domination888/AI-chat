#!/bin/bash
# 下载 SenseVoice 模型到 packaging/cache/asr-models（构建时执行一次）
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/packaging/cache/asr-models"
mkdir -p "$DEST"

export MODELSCOPE_CACHE="$DEST"
export FUNASR_MODEL_DIR="$DEST"

echo "📥 Downloading SenseVoiceSmall + VAD models to $DEST ..."

python3 - <<'PY'
import os
os.environ.setdefault("MODELSCOPE_CACHE", os.environ.get("MODELSCOPE_CACHE", ""))
os.environ.setdefault("FUNASR_MODEL_DIR", os.environ.get("FUNASR_MODEL_DIR", ""))
from funasr import AutoModel
AutoModel(
    model="iic/SenseVoiceSmall",
    vad_model="fsmn-vad",
    device="cpu",
    disable_update=True,
)
print("models ready")
PY

echo "✅ ASR models cached at $DEST"
