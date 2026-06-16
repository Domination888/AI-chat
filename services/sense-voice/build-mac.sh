#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
ROOT="$(cd ../.. && pwd)"

echo "🔨 Building SenseVoice ASR binary (macOS)..."
pip3 install -q pyinstaller
pyinstaller --noconfirm --clean build.spec

mkdir -p "$ROOT/packaging/cache/asr-bin/mac"
cp -f dist/sensevoice-server "$ROOT/packaging/cache/asr-bin/mac/sensevoice-server"
chmod +x "$ROOT/packaging/cache/asr-bin/mac/sensevoice-server"
echo "✅ $ROOT/packaging/cache/asr-bin/mac/sensevoice-server"
