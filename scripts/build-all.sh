#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
npm --prefix apps/desktop run check
npm --prefix apps/desktop run build:cloud
npm --prefix apps/desktop run build:admin
rm -rf apps/server/src/main/resources/static/admin
mkdir -p apps/server/src/main/resources/static/admin
cp -R apps/desktop/dist-admin/. apps/server/src/main/resources/static/admin/
./tools/maven/mvnw -B -f apps/server/pom.xml clean verify
mkdir -p artifacts
cp apps/server/target/story-runtime-0.1.0.jar artifacts/story-cloud-runtime.jar
./tools/maven/mvnw -B -f apps/server/pom.xml -Poffline clean verify
