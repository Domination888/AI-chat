#!/bin/bash

# AI-Chat 完整开发环境启动脚本
# 包含：后端、前端、客户端、ASR、TTS

set -e

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOG_DIR="$PROJECT_ROOT/unified-logs"
PID_DIR="$LOG_DIR/pids"
PID_FILE="$PID_DIR/pids.txt"
CLEANUP_EXIT_CODE=0

info() {
    printf '[INFO] %s\n' "$*"
}

error() {
    printf '[ERROR] %s\n' "$*" >&2
}

mkdir -p "$PID_DIR"

info "Starting AI-Chat Complete Development Environment..."

# 递归收集 PID 自身 + 所有子孙进程
collect_descendants() {
    local pid=$1
    [ -z "$pid" ] && return 0

    echo "$pid"

    local children
    children=$(pgrep -P "$pid" 2>/dev/null || true)

    local child
    for child in $children; do
        collect_descendants "$child"
    done
}

# 只按 PID 日志清理本项目记录的进程，不按端口杀未知进程。
kill_tree() {
    local pid=$1
    [ -z "$pid" ] && return 0
    kill -0 "$pid" 2>/dev/null || return 0

    local all
    all=$(collect_descendants "$pid" | sort -u)
    [ -z "$all" ] && return 0

    echo "$all" | xargs kill 2>/dev/null || true

    local i=0
    local alive=0
    while [ "$i" -lt 3 ]; do
        alive=0
        local p
        for p in $all; do
            if kill -0 "$p" 2>/dev/null; then
                alive=1
                break
            fi
        done
        [ "$alive" -eq 0 ] && break
        sleep 1
        i=$((i + 1))
    done

    if [ "$alive" -ne 0 ]; then
        echo "$all" | xargs kill -9 2>/dev/null || true
    fi
}

stop_services_from_pid_file() {
    [ -f "$PID_FILE" ] || return 0

    info "Stopping existing services from $PID_FILE..."
    while read -r name pid; do
        [ -z "$pid" ] && continue
        info "Stopping $name ($pid)"
        kill_tree "$pid"
    done < "$PID_FILE"
}

ensure_ports_available() {
    local unavailable=0
    local port
    for port in "$@"; do
        if lsof -ti:"$port" >/dev/null; then
            error "Port $port is still in use, but it is not safe to kill by port."
            error "Run ./startup-scripts/stop-all.sh or remove the conflicting process manually."
            unavailable=1
        fi
    done

    return "$unavailable"
}

# 清理函数
cleanup() {
    local exit_code="${CLEANUP_EXIT_CODE:-0}"
    printf '\n'
    info "Stopping all services..."
    stop_services_from_pid_file
    rm -f "$PID_FILE"
    info "All services stopped."
    exit "$exit_code"
}

# 设置清理陷阱
trap cleanup INT TERM

# 检查服务是否启动成功
check_service() {
    local url=$1
    local name=$2
    local max_attempts=10
    local attempt=1
    
    info "Waiting for $name to start..."
    
    while [ $attempt -le $max_attempts ]; do
        if curl -fsS --max-time 5 "$url" > /dev/null; then
            info "$name is running!"
            return 0
        fi
        sleep 3
        attempt=$((attempt + 1))
    done
    
    error "$name failed to start"
    return 1
}

# 抓取端口上的真实监听 PID（处理 mvnw / npm 这类 wrapper 进程，$! 不是真身的情况）
# 用法：pid=$(resolve_port_pid 8080)
resolve_port_pid() {
    local port=$1
    # 取 LISTEN 状态的最早 PID（多个时按 PID 升序，通常是父监听进程）
    lsof -nP -iTCP:"$port" -sTCP:LISTEN -t 2>/dev/null | sort -n | head -1
}

# 启动 SearXNG（本地联网搜索后端，Docker）
start_searxng() {
    if ! command -v docker >/dev/null 2>&1; then
        info "Docker 未安装，跳过 SearXNG（联网搜索将不可用）"
        return 0
    fi
    info "Starting SearXNG (Docker, :8888)..."
    docker compose -f "$PROJECT_ROOT/services/searxng/docker-compose.yml" up -d >/dev/null 2>&1 || true
    if check_service "http://localhost:8888/search?q=test&format=json" "SearXNG"; then
        :
    else
        info "SearXNG 未就绪，可稍后 docker logs searxng 查看"
    fi
}

# 启动后端
start_backend() {
    info "Starting Spring Boot backend..."
    mkdir -p "$LOG_DIR/backend"
    cd "$PROJECT_ROOT/backend"
    ./mvnw spring-boot:run > "$LOG_DIR/backend/app.log" 2>&1 &
    cd "$PROJECT_ROOT"

    # /api/health 不访问数据库，不能代表角色数据已经可读。
    # 直接探测角色接口，避免 Electron 首屏请求撞上 Hikari/MySQL 首次建连。
    if ! check_service "http://localhost:8080/api/roles" "Backend and database"; then
        error "Backend failed to start. Check $LOG_DIR/backend/app.log"
        return 1
    fi
}

# 启动前端
start_frontend() {
    info "Starting Vite frontend server..."
    mkdir -p "$LOG_DIR/frontend"
    cd "$PROJECT_ROOT/client/src"
    npm run dev > "$LOG_DIR/frontend/app.log" 2>&1 &
    cd "$PROJECT_ROOT"

    if ! check_service "http://localhost:3000" "Frontend"; then
        error "Frontend failed to start. Check $LOG_DIR/frontend/app.log"
        return 1
    fi
}

# 启动Electron客户端（无监听端口，用 $! 即可）
start_client() {
    info "Starting Electron client..."
    cd "$PROJECT_ROOT/client"
    # Electron 平时几乎无有效 stdout；设 CLIENT_LOG=1 才写入 unified-logs/client/app.log
    if [ "${CLIENT_LOG:-}" = "1" ]; then
        mkdir -p "$LOG_DIR/client"
        npx electron . > "$LOG_DIR/client/app.log" 2>&1 &
    else
        npx electron . >/dev/null 2>&1 &
    fi
    CLIENT_PID=$!
    cd "$PROJECT_ROOT"
    info "Electron client is starting..."
}

# 启动ASR服务
start_asr() {
    info "Starting SenseVoice ASR service..."
    mkdir -p "$LOG_DIR/asr"
    cd "$PROJECT_ROOT/services/sense-voice"
    python server.py > "$LOG_DIR/asr/app.log" 2>&1 &
    cd "$PROJECT_ROOT"

    if ! check_service "http://localhost:9000/healthz" "ASR Service"; then
        error "ASR service failed to start. Check $LOG_DIR/asr/app.log"
        return 1
    fi
}

# 检查 TTS 服务（AstraTTS 独立启动，这里只做健康检查）
get_tts_base_url() {
    python3 - <<'PY'
import json
from pathlib import Path

cfg = Path("config/runtime-config.json")
try:
    data = json.loads(cfg.read_text(encoding="utf-8"))
    url = (data.get("voice") or {}).get("astraTtsBaseUrl") or "http://localhost:5000"
except Exception:
    url = "http://localhost:5000"
print(url.rstrip("/"))
PY
}

start_tts() {
    local tts_base_url
    tts_base_url="$(get_tts_base_url)"
    local tts_url="${tts_base_url}/api/tts/status"
    info "Checking Astra TTS service at $tts_base_url..."
    if curl -s --max-time 5 "$tts_url" > /dev/null 2>&1; then
        info "Astra TTS service is reachable at $tts_base_url"
    else
        info "Astra TTS service not reachable at $tts_url"
        info "Make sure the AstraTTS service is running."
    fi
}

# 显示状态信息
show_status() {
    printf '\n'
    info "AI-Chat Development Environment is ready!"
    info "Frontend: http://localhost:3000"
    info "Backend: http://localhost:8080/api/health"
    info "SearXNG: http://localhost:8888 (本地联网搜索)"
    info "ASR: http://localhost:9000/healthz"
    info "TTS: $(get_tts_base_url) (AstraTTS)"
    info "Electron: Desktop app should appear"
    printf '\n'
    info "Unified Logs Location: $LOG_DIR/"
    info "Backend: $LOG_DIR/backend/app.log"
    info "Frontend: $LOG_DIR/frontend/app.log"
    info "Client: CLIENT_LOG=1 时写入 $LOG_DIR/client/app.log"
    info "ASR: $LOG_DIR/asr/app.log"
    info "TTS: external AstraTTS service (see AstraTTS logs)"
    printf '\n'
    info "To stop all services: Press Ctrl+C or run kill \$(awk '{print \$2}' $PID_FILE)"
    info "Or use: ./startup-scripts/stop-all.sh"
}

upsert_pid() {
    local name="$1"
    local pid="$2"
    [ -z "$pid" ] && return 0
    if [ -f "$PID_FILE" ]; then
        grep -v "^$name " "$PID_FILE" > "$PID_FILE.tmp" || true
        mv "$PID_FILE.tmp" "$PID_FILE"
    fi
    printf '%s %s\n' "$name" "$pid" >> "$PID_FILE"
}

# 保存PID（统一写入 pids.txt，避免重复）
save_pids() {
    # mvnw / npm 这类 wrapper 的 $! 不一定是真正监听进程；健康检查完成后统一按端口解析。
    BACKEND_PID=$(resolve_port_pid 8080)
    FRONTEND_PID=$(resolve_port_pid 3000)
    ASR_PID=$(resolve_port_pid 9000)

    upsert_pid "backend" "$BACKEND_PID"
    upsert_pid "frontend" "$FRONTEND_PID"
    upsert_pid "client" "$CLIENT_PID"
    upsert_pid "asr" "$ASR_PID"
}

wait_for_job() {
    local pid="$1"
    local name="$2"
    if wait "$pid"; then
        return 0
    fi

    error "$name failed during startup"
    return 1
}

# 主流程
main() {
    local failed=0

    stop_services_from_pid_file
    rm -f "$PID_FILE"
    if ! ensure_ports_available 8080 3000 9000; then
        return 1
    fi
    > "$PID_FILE"

    # 后端、前端、ASR、SearXNG、TTS 互不阻塞，并发启动并各自做健康检查。
    start_searxng &
    SEARXNG_JOB=$!
    start_backend &
    BACKEND_JOB=$!
    start_frontend &
    FRONTEND_JOB=$!
    start_asr &
    ASR_JOB=$!
    start_tts &
    TTS_JOB=$!

    # Electron 首屏会立即读取角色列表，必须同时等 Vite 和数据库可用。
    local frontend_ready=0
    local backend_ready=0
    wait_for_job "$FRONTEND_JOB" "Frontend" && frontend_ready=1 || failed=1
    wait_for_job "$BACKEND_JOB" "Backend" && backend_ready=1 || failed=1
    if [ "$frontend_ready" -eq 1 ] && [ "$backend_ready" -eq 1 ]; then
        start_client
    fi

    wait_for_job "$ASR_JOB" "ASR Service" || failed=1
    wait_for_job "$SEARXNG_JOB" "SearXNG" || true
    wait_for_job "$TTS_JOB" "TTS check" || true

    save_pids

    if [ "$failed" -ne 0 ]; then
        error "Startup failed. Check logs under $LOG_DIR/"
        CLEANUP_EXIT_CODE=1
        cleanup
    fi

    show_status

    while true; do
        sleep 3600 &
        wait $!
    done
}

# 执行主流程
main
