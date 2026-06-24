#!/bin/bash

# AI-Chat 完整开发环境启动脚本
# 包含：后端、前端、客户端、ASR、TTS

set -e

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOG_DIR="$PROJECT_ROOT/unified-logs"
PID_DIR="$LOG_DIR/pids"
PID_FILE="$PID_DIR/pids.txt"

mkdir -p "$PID_DIR"

info() {
    printf '[INFO] %s\n' "$*"
}

error() {
    printf '[ERROR] %s\n' "$*" >&2
}

# 启动前清空 pids.txt，避免历史残留 PID 越积越多
> "$PID_FILE"

info "Starting AI-Chat Complete Development Environment..."

# 清理函数
cleanup() {
    printf '\n'
    info "Stopping all services..."
    if [ -f "$PID_FILE" ]; then
        awk '{print $2}' "$PID_FILE" | xargs kill -9 2>/dev/null || true
        rm -f "$PID_FILE"
    fi
    info "All services stopped."
    exit 0
}

# 设置清理陷阱
trap cleanup INT TERM

# 检查端口并清理
check_and_kill_port() {
    local port=$1
    if lsof -ti:$port >/dev/null; then
        info "Port $port is in use, killing existing processes..."
        lsof -ti:$port | xargs kill -9 2>/dev/null || true
        sleep 2
    fi
}

# 检查服务是否启动成功
check_service() {
    local url=$1
    local name=$2
    local max_attempts=10
    local attempt=1
    
    info "Waiting for $name to start..."
    
    while [ $attempt -le $max_attempts ]; do
        if curl -s --max-time 5 $url > /dev/null; then
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
    check_and_kill_port 8080

    info "Starting Spring Boot backend..."
    mkdir -p "$LOG_DIR/backend"
    cd "$PROJECT_ROOT/backend"
    ./mvnw spring-boot:run > "$LOG_DIR/backend/app.log" 2>&1 &
    cd "$PROJECT_ROOT"

    if ! check_service "http://localhost:8080/api/health" "Backend"; then
        error "Backend failed to start. Check $LOG_DIR/backend/app.log"
        return 1
    fi
    # mvnw 是 wrapper，真正监听 8080 的是 spawn 出来的 java 进程
    BACKEND_PID=$(resolve_port_pid 8080)
}

# 启动前端
start_frontend() {
    check_and_kill_port 3000

    info "Starting Vite frontend server..."
    mkdir -p "$LOG_DIR/frontend"
    cd "$PROJECT_ROOT/client/src"
    npm run dev > "$LOG_DIR/frontend/app.log" 2>&1 &
    cd "$PROJECT_ROOT"

    if ! check_service "http://localhost:3000" "Frontend"; then
        error "Frontend failed to start. Check $LOG_DIR/frontend/app.log"
        return 1
    fi
    # npm 是 wrapper，真正监听 3000 的是 vite/esbuild 子进程
    FRONTEND_PID=$(resolve_port_pid 3000)
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
    check_and_kill_port 9000

    info "Starting SenseVoice ASR service..."
    mkdir -p "$LOG_DIR/asr"
    cd "$PROJECT_ROOT/services/sense-voice"
    python server.py > "$LOG_DIR/asr/app.log" 2>&1 &
    cd "$PROJECT_ROOT"

    if ! check_service "http://localhost:9000/healthz" "ASR Service"; then
        error "ASR service failed to start. Check $LOG_DIR/asr/app.log"
        return 1
    fi
    # python server.py 一般 $! 即真身，但仍以端口为准更稳
    ASR_PID=$(resolve_port_pid 9000)
}

# 启动TTS服务（已迁移到 Win，仅做健康检查）
start_tts() {
    local tts_url="http://192.168.124.2:5000/api/tts/status"
    info "Checking Astra TTS service on Win..."
    if curl -s --max-time 5 "$tts_url" > /dev/null 2>&1; then
        info "Astra TTS service is reachable on Win (:5000)"
    else
        info "Astra TTS service not reachable at $tts_url"
        info "Make sure the TTS service is running on Win."
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
    info "TTS: http://192.168.124.2:5000 (Astra on Win)"
    info "Electron: Desktop app should appear"
    printf '\n'
    info "Unified Logs Location: $LOG_DIR/"
    info "Backend: $LOG_DIR/backend/app.log"
    info "Frontend: $LOG_DIR/frontend/app.log"
    info "Client: CLIENT_LOG=1 时写入 $LOG_DIR/client/app.log"
    info "ASR: $LOG_DIR/asr/app.log"
    info "TTS: remote Astra service on Win (no local log)"
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
    upsert_pid "backend" "$BACKEND_PID"
    upsert_pid "frontend" "$FRONTEND_PID"
    upsert_pid "client" "$CLIENT_PID"
    upsert_pid "asr" "$ASR_PID"
}

# 主流程
main() {
    # 启动各个服务
    start_searxng
    start_backend
    start_frontend
    start_client
    start_asr
    start_tts
    
    # 保存PID信息
    save_pids
    
    # 显示状态
    show_status
    
    # 等待用户中断
    wait
}

# 执行主流程
main
