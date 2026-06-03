#!/bin/bash

# AI-Chat 完整开发环境启动脚本
# 包含：后端、前端、客户端、ASR、TTS

set -e

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOG_DIR="$PROJECT_ROOT/unified-logs"
PID_DIR="$LOG_DIR/pids"
PID_FILE="$PID_DIR/pids.txt"

# 创建日志目录
mkdir -p "$LOG_DIR"/{backend,frontend,client,electron,asr,tts}
mkdir -p "$PID_DIR"

# 启动前清空 pids.txt，避免历史残留 PID 越积越多
> "$PID_FILE"

echo "🚀 Starting AI-Chat Complete Development Environment..."

# 清理函数
cleanup() {
    echo ""
    echo "🛑 Stopping all services..."
    if [ -f "$PID_FILE" ]; then
        awk '{print $2}' "$PID_FILE" | xargs kill -9 2>/dev/null || true
        rm -f "$PID_FILE"
    fi
    echo "✅ All services stopped."
    exit 0
}

# 设置清理陷阱
trap cleanup INT TERM

# 检查端口并清理
check_and_kill_port() {
    local port=$1
    if lsof -ti:$port >/dev/null; then
        echo "⚠️  Port $port is in use, killing existing processes..."
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
    
    echo "⏳ Waiting for $name to start..."
    
    while [ $attempt -le $max_attempts ]; do
        if curl -s --max-time 5 $url > /dev/null; then
            echo "✅ $name is running!"
            return 0
        fi
        sleep 3
        attempt=$((attempt + 1))
    done
    
    echo "❌ $name failed to start"
    return 1
}

# 抓取端口上的真实监听 PID（处理 mvnw / npm 这类 wrapper 进程，$! 不是真身的情况）
# 用法：pid=$(resolve_port_pid 8080)
resolve_port_pid() {
    local port=$1
    # 取 LISTEN 状态的最早 PID（多个时按 PID 升序，通常是父监听进程）
    lsof -nP -iTCP:"$port" -sTCP:LISTEN -t 2>/dev/null | sort -n | head -1
}

# 启动后端
start_backend() {
    check_and_kill_port 8080

    echo "📦 Starting Spring Boot backend..."
    cd "$PROJECT_ROOT/backend"
    ./mvnw spring-boot:run > "$LOG_DIR/backend/app.log" 2>&1 &
    cd "$PROJECT_ROOT"

    if ! check_service "http://localhost:8080/api/health" "Backend"; then
        echo "❌ Backend failed to start. Check $LOG_DIR/backend/app.log"
        return 1
    fi
    # mvnw 是 wrapper，真正监听 8080 的是 spawn 出来的 java 进程
    BACKEND_PID=$(resolve_port_pid 8080)
}

# 启动前端
start_frontend() {
    check_and_kill_port 3000

    echo "🎨 Starting Vite frontend server..."
    cd "$PROJECT_ROOT/client/src"
    npm run dev > "$LOG_DIR/frontend/app.log" 2>&1 &
    cd "$PROJECT_ROOT"

    if ! check_service "http://localhost:3000" "Frontend"; then
        echo "❌ Frontend failed to start. Check $LOG_DIR/frontend/app.log"
        return 1
    fi
    # npm 是 wrapper，真正监听 3000 的是 vite/esbuild 子进程
    FRONTEND_PID=$(resolve_port_pid 3000)
}

# 启动Electron客户端（无监听端口，用 $! 即可）
start_client() {
    echo "🖥️  Starting Electron client..."
    cd "$PROJECT_ROOT/client"
    npx electron . > "$LOG_DIR/client/app.log" 2>&1 &
    ELECTRON_PID=$!
    cd "$PROJECT_ROOT"
    echo "✅ Electron client is starting..."
}

# 启动ASR服务
start_asr() {
    check_and_kill_port 9000

    echo "🎤 Starting SenseVoice ASR service..."
    cd "$PROJECT_ROOT/services/sense-voice"
    python server.py > "$LOG_DIR/asr/app.log" 2>&1 &
    cd "$PROJECT_ROOT"

    if ! check_service "http://localhost:9000/healthz" "ASR Service"; then
        echo "❌ ASR service failed to start. Check $LOG_DIR/asr/app.log"
        return 1
    fi
    # python server.py 一般 $! 即真身，但仍以端口为准更稳
    ASR_PID=$(resolve_port_pid 9000)
}

# 启动TTS服务（已迁移到 Win，仅做健康检查）
start_tts() {
    local tts_url="http://192.168.124.2:5000/api/tts/status"
    echo "🔊 Checking Astra TTS service on Win..."
    if curl -s --max-time 5 "$tts_url" > /dev/null 2>&1; then
        echo "✅ Astra TTS service is reachable on Win (:5000)"
    else
        echo "⚠️  Astra TTS service not reachable at $tts_url"
        echo "   Make sure the TTS service is running on Win."
    fi
}

# 显示状态信息
show_status() {
    echo ""
    echo "🎯 AI-Chat Development Environment is ready!"
    echo "📱 Frontend: http://localhost:3000"
    echo "🔧 Backend: http://localhost:8080/api/health"
    echo "🎤 ASR: http://localhost:9000/healthz"
    echo "🔊 TTS: http://192.168.124.2:5000 (Astra on Win)"
    echo "🖥️  Electron: Desktop app should appear"
    echo ""
    echo "📊 Unified Logs Location: $LOG_DIR/"
    echo "   Backend: $LOG_DIR/backend/app.log"
    echo "   Frontend: $LOG_DIR/frontend/app.log"
    echo "   Client: $LOG_DIR/client/app.log"
    echo "   ASR: $LOG_DIR/asr/app.log"
    echo "   TTS: remote Astra service on Win (no local log)"
    echo ""
    echo "🛑 To stop all services: Press Ctrl+C or run kill \$(awk '{print \$2}' $PID_FILE)"
    echo "   Or use: ./startup-scripts/stop-all.sh"
}

upsert_pid() {
    local name="$1"
    local pid="$2"
    [ -z "$pid" ] && return 0
    if [ -f "$PID_FILE" ]; then
        grep -v "^$name " "$PID_FILE" > "$PID_FILE.tmp" || true
        mv "$PID_FILE.tmp" "$PID_FILE"
    fi
    echo "$name $pid" >> "$PID_FILE"
}

# 保存PID（统一写入 pids.txt，避免重复）
save_pids() {
    upsert_pid "backend" "$BACKEND_PID"
    upsert_pid "frontend" "$FRONTEND_PID"
    upsert_pid "electron" "$ELECTRON_PID"
    upsert_pid "asr" "$ASR_PID"
}

# 主流程
main() {
    # 启动各个服务
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