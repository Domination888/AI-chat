#!/bin/bash
# 组装 electron-builder extraResources 到 packaging/staging/{mac|win}
set -euo pipefail

PLATFORM="${1:-mac}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
STAGE="$ROOT/packaging/staging/$PLATFORM"
CACHE="$ROOT/packaging/cache/$PLATFORM"
ASR_MODELS="$ROOT/packaging/cache/asr-models"
ASR_BIN="$ROOT/packaging/cache/asr-bin/$PLATFORM"

rm -rf "$STAGE"
mkdir -p "$STAGE"/{backend,mcp,asr/models,jre,memos,searxng}

log() { echo "[stage-runtime] $*"; }

# Backend + MCP
cp -f "$ROOT/backend/target/AI-Chat-0.0.1-SNAPSHOT.jar" "$STAGE/backend/"
cp -f "$ROOT/services/prime-mcp-server/target/prime-mcp-server-1.0.0.jar" "$STAGE/mcp/"

# JRE — 优先 cache 完整 JRE，其次 jlink，最后复制 JAVA_HOME
if [[ -d "$CACHE/jre" && -x "$CACHE/jre/bin/java" ]]; then
  mkdir -p "$STAGE/jre"
  cp -a "$CACHE/jre/." "$STAGE/jre/"
  log "staged JRE from cache"
elif [[ -x "$ROOT/packaging/scripts/create-jre.sh" ]]; then
  "$ROOT/packaging/scripts/create-jre.sh" "$STAGE/jre" || true
fi
if [[ ! -x "$STAGE/jre/bin/java" && -n "${JAVA_HOME:-}" ]]; then
  log "copy JAVA_HOME as bundled JRE"
  mkdir -p "$STAGE/jre"
  cp -a "$JAVA_HOME/." "$STAGE/jre/"
fi

# Portable infra from cache
for svc in redis mysql neo4j qdrant; do
  if [[ -d "$CACHE/$svc" ]]; then
    cp -a "$CACHE/$svc" "$STAGE/$svc"
    log "staged $svc"
  else
    log "WARN: missing cache/$PLATFORM/$svc — packaged app will fail to start $svc"
  fi
done

# ASR bundle (venv + models) or PyInstaller binary
ASR_BUNDLE="$ROOT/packaging/cache/asr-bundle"
if [[ -d "$ASR_BUNDLE" ]]; then
  cp -a "$ASR_BUNDLE/." "$STAGE/asr/"
  chmod +x "$STAGE/asr/start-asr.sh" 2>/dev/null || true
  log "staged ASR bundle"
elif [[ "$PLATFORM" == "mac" && -f "$ASR_BIN/sensevoice-server" ]]; then
  cp -f "$ASR_BIN/sensevoice-server" "$STAGE/asr/"
  chmod +x "$STAGE/asr/sensevoice-server"
elif [[ "$PLATFORM" == "win" && -f "$ASR_BIN/sensevoice-server.exe" ]]; then
  cp -f "$ASR_BIN/sensevoice-server.exe" "$STAGE/asr/"
fi
if [[ -d "$ASR_MODELS" ]]; then
  cp -a "$ASR_MODELS/." "$STAGE/asr/models/"
  log "staged ASR models"
else
  log "WARN: run ./scripts/download-asr-models.sh first"
fi

# MemOS + SearXNG templates
cp -a "$ROOT/packaging/templates/memos/." "$STAGE/memos/"
cp -a "$ROOT/packaging/templates/searxng/." "$STAGE/searxng/"
if [[ -d "$ROOT/packaging/templates/memos/venv" ]]; then
  cp -a "$ROOT/packaging/templates/memos/venv" "$STAGE/memos/"
fi
if [[ -d "$ROOT/packaging/templates/searxng/venv" ]]; then
  cp -a "$ROOT/packaging/templates/searxng/venv" "$STAGE/searxng/"
fi
if [[ -d "$ROOT/packaging/templates/memos/src" ]]; then
  cp -a "$ROOT/packaging/templates/memos/src" "$STAGE/memos/"
fi

# Bundled config (init.sql for mysql first-run)
mkdir -p "$ROOT/packaging/config"
cp -f "$ROOT/backend/init.sql" "$ROOT/packaging/config/init.sql"
cp -f "$ROOT/packaging/config/fix-encoding.sql" "$ROOT/packaging/config/fix-encoding.sql" 2>/dev/null || true

# 确保 ASR 自带 ffmpeg（webm 转码）
if [[ -d "$STAGE/asr" && ! -x "$STAGE/asr/ffmpeg" ]]; then
  FFM=""
  for PY in "$STAGE/asr/venv/bin/python" "$ROOT/packaging/templates/asr/venv/bin/python"; do
    if [[ -x "$PY" ]]; then
      FFM=$("$PY" -c "import imageio_ffmpeg; print(imageio_ffmpeg.get_ffmpeg_exe())" 2>/dev/null || true)
      [[ -n "$FFM" && -f "$FFM" ]] && break
    fi
  done
  if [[ -n "$FFM" && -f "$FFM" ]]; then
    cp -f "$FFM" "$STAGE/asr/ffmpeg"
    chmod +x "$STAGE/asr/ffmpeg"
    log "staged ASR ffmpeg"
  else
    log "WARN: ASR ffmpeg missing — pip install imageio-ffmpeg in ASR venv, then rerun stage-runtime"
  fi
fi

chmod +x "$STAGE/memos/start-memos.sh" 2>/dev/null || true
chmod +x "$STAGE/searxng/start-searxng.sh" 2>/dev/null || true

log "Staged → $STAGE"
# electron-builder on mac resolves extraResources via staging/darwin
if [[ "$PLATFORM" == "mac" ]]; then
  ln -sfn mac "$ROOT/packaging/staging/darwin"
fi
if [[ "$PLATFORM" == "win" ]]; then
  ln -sfn win "$ROOT/packaging/staging/win32"
fi
