#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
npm --prefix apps/desktop ci
./tools/maven/mvnw -B -f apps/server/pom.xml dependency:resolve
printf '%s\n' 'Dependencies ready. Run npm run dev:offline or npm run dev:commercial; see docs/packaging.md for model and native packaging prerequisites.'
