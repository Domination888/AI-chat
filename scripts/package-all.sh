#!/bin/bash
# 一键打包 AI-Chat 桌面应用（dmg / exe）
# 用法:
#   ./scripts/package-all.sh mac
#   ./scripts/package-all.sh win
set -euo pipefail

PLATFORM="${1:-mac}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

echo "🏗️  Step 1/4: build backend + frontend + MCP jars"
"$ROOT/scripts/build-all.sh"

echo "📥 Step 2/4: optional ASR models (skip if cached)"
if [[ ! -d "$ROOT/packaging/cache/asr-models" ]]; then
  bash "$ROOT/scripts/download-asr-models.sh" || echo "WARN: ASR model download failed — continue if models already cached"
fi

echo "📦 Step 3/4: stage runtime → packaging/staging/$PLATFORM"
chmod +x "$ROOT/packaging/stage-runtime.sh" "$ROOT/packaging/fetch-runtime.sh" 2>/dev/null || true
bash "$ROOT/packaging/stage-runtime.sh" "$PLATFORM"

echo "🖥️  Step 4/4: electron-builder"
cd "$ROOT/client"
# GitHub 直连常超时；优先走国内镜像（可通过环境变量覆盖）
export ELECTRON_MIRROR="${ELECTRON_MIRROR:-https://npmmirror.com/mirrors/electron/}"
export ELECTRON_BUILDER_BINARIES_MIRROR="${ELECTRON_BUILDER_BINARIES_MIRROR:-https://npmmirror.com/mirrors/electron-builder-binaries/}"
npm install
if [[ "$PLATFORM" == "mac" ]]; then
  npm run build:mac
elif [[ "$PLATFORM" == "win" ]]; then
  npm run build:win
else
  echo "Unknown platform: $PLATFORM (use mac|win)" >&2
  exit 1
fi

echo ""
echo "✅ Package complete → client/release/"
