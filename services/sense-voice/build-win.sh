#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
ROOT="$(cd ../.. && pwd)"

echo "🔨 Building SenseVoice ASR binary (Windows — run on Win)..."
pip install -q pyinstaller
pyinstaller --noconfirm --clean build.spec

mkdir -p "$ROOT/packaging/cache/asr-bin/win"
cp -f dist/sensevoice-server.exe "$ROOT/packaging/cache/asr-bin/win/sensevoice-server.exe"
echo "✅ $ROOT/packaging/cache/asr-bin/win/sensevoice-server.exe"
