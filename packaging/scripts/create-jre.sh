#!/bin/bash
# 用 jlink 生成 Spring Boot 兼容的 trimmed JRE
set -euo pipefail
DEST="${1:?output dir}"

# java.desktop 提供 java.beans.*（Spring 绑定配置必需）；java.sql 供 JDBC
DEFAULT_MODULES="java.base,java.logging,java.xml,java.naming,java.management,java.instrument,java.desktop,java.sql,java.net.http,java.security.jgss,jdk.crypto.ec,jdk.unsupported,jdk.management"
MODULES="${JLINK_MODULES:-$DEFAULT_MODULES}"

if [[ -z "${JAVA_HOME:-}" ]]; then
  echo "JAVA_HOME required" >&2
  exit 1
fi

rm -rf "$DEST"
"$JAVA_HOME/bin/jlink" \
  --add-modules "$MODULES" \
  --strip-debug \
  --no-man-pages \
  --no-header-files \
  --compress=2 \
  --output "$DEST"

echo "JRE → $DEST"
