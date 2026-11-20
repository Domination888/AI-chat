#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
exec bash "$ROOT/scripts/release/build.sh" "${1:-offline}" "${2:-}" "${@:3}"
