#!/usr/bin/env bash
# SearXNG 无 Docker 启动
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
VENV="$DIR/venv"
SRC="$DIR/src"
PORT="${SEARXNG_PORT:-8888}"

if [[ ! -d "$VENV" ]]; then
  echo "SearXNG venv missing — run setup-venv.sh during build" >&2
  exit 1
fi
if [[ ! -d "$SRC/searx" ]]; then
  echo "Official SearXNG source missing — run setup-venv.sh during build" >&2
  exit 1
fi

# shellcheck disable=SC1091
source "$VENV/bin/activate"
export SEARXNG_SETTINGS_PATH="$DIR/settings.yml"
export GRANIAN_HOST="127.0.0.1"
export GRANIAN_PORT="$PORT"
export PYTHONPATH="$SRC"

cd "$DIR"
exec granian --interface wsgi searx.webapp:app --host 127.0.0.1 --port "$PORT"
