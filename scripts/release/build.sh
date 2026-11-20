#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
MODE="${1:-offline}"
PLATFORM="${2:-}"
case "$MODE" in cloud|commercial|offline|admin) ;; *) echo 'Use commercial|offline|admin (cloud is an alias)' >&2; exit 1 ;; esac
ARGS=()
if [[ -n "$PLATFORM" ]]; then
  case "$PLATFORM" in mac|win) ARGS+=("--$PLATFORM") ;; *) echo 'Use mac|win or omit platform' >&2; exit 1 ;; esac
fi
if [[ "$MODE" == admin ]]; then ARGS=(); fi
exec node "$ROOT/scripts/workspace.cjs" package "$MODE" "${ARGS[@]}" "${@:3}"
