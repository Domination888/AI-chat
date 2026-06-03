#!/bin/bash

# AI-Chat 完整开发环境停止脚本

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PID_DIR="$PROJECT_ROOT/unified-logs/pids"
PID_FILE="$PID_DIR/pids.txt"

echo "🛑 Stopping AI-Chat Development Environment..."

# 递归收集 PID 自身 + 所有子孙进程
# 用法：collect_descendants <pid>
collect_descendants() {
    local pid=$1
    [ -z "$pid" ] && return 0
    # 自身
    echo "$pid"
    # 直接子进程
    local children
    children=$(pgrep -P "$pid" 2>/dev/null)
    local c
    for c in $children; do
        collect_descendants "$c"
    done
}

# 优雅杀 → 等 → 强杀（含子孙）
# 用法：kill_tree <pid>
kill_tree() {
    local pid=$1
    [ -z "$pid" ] && return 0
    # kill -0 检查存活
    kill -0 "$pid" 2>/dev/null || return 0

    local all
    all=$(collect_descendants "$pid" | sort -u)
    [ -z "$all" ] && return 0

    # SIGTERM
    echo "$all" | xargs kill 2>/dev/null || true
    # 给最多 3 秒优雅退出
    local i=0
    while [ $i -lt 3 ]; do
        # 还有任何一个活着就继续等
        local alive=0
        for p in $all; do
            if kill -0 "$p" 2>/dev/null; then alive=1; break; fi
        done
        [ $alive -eq 0 ] && break
        sleep 1
        i=$((i + 1))
    done
    # SIGKILL 兜底
    echo "$all" | xargs kill -9 2>/dev/null || true
}

# 1) 从 PID 文件杀进程（递归子树）
if [ -f "$PID_FILE" ]; then
    echo "🔍 Killing processes from PID file (with descendants)..."
    while read -r name pid; do
        [ -z "$pid" ] && continue
        echo "   - $name ($pid)"
        kill_tree "$pid"
    done < "$PID_FILE"
    rm -f "$PID_FILE"
fi

# 2) 按端口兜底杀进程（防止 PID 漂移、wrapper 残留）
# TTS 已迁移到 Win (Astra :5000)，Mac 端不再有本地 TTS 服务
ports=(8080 3000 9000)
for port in "${ports[@]}"; do
    pids=$(lsof -nP -iTCP:"$port" -sTCP:LISTEN -t 2>/dev/null)
    if [ -n "$pids" ]; then
        echo "🔍 Killing processes on port $port: $pids"
        for p in $pids; do
            kill_tree "$p"
        done
    fi
done

echo "✅ AI-Chat Development Environment has been stopped."