#!/usr/bin/env bash
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
VENV="$DIR/venv"
SRC="$DIR/src"
REF="${SEARXNG_REF:-master}"

PYTHON="${PYTHON:-/opt/homebrew/opt/python@3.12/bin/python3.12}"
if [[ ! -x "$PYTHON" ]]; then PYTHON="$(command -v python3.12 || command -v python3)"; fi

"$PYTHON" -m venv "$VENV"
# shellcheck disable=SC1091
source "$VENV/bin/activate"
pip install -U pip
if [[ -d "$SRC/.git" ]]; then
  git -C "$SRC" fetch --depth 1 origin "$REF"
  git -C "$SRC" checkout --detach FETCH_HEAD
else
  rm -rf "$SRC"
  git clone --depth 1 --branch "$REF" https://github.com/searxng/searxng.git "$SRC"
fi
pip install -r "$SRC/requirements.txt" -r "$SRC/requirements-server.txt" granian
cp -f "$DIR/../../../services/searxng/searxng/settings.yml" "$DIR/settings.yml" 2>/dev/null || \
  cp -f "$DIR/settings.yml.example" "$DIR/settings.yml"
PYTHONPATH="$SRC" python -c "import searx; print('SearXNG source ready:', searx.__file__)"
echo "SearXNG venv and official source ready"
