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

# 启动后端
start_backend() {
    check_and_kill_port 8080
    
    echo "📦 Starting Spring Boot backend..."
    cd "$PROJECT_ROOT/backend"
    ./mvnw spring-boot:run > "$LOG_DIR/backend/app.log" 2>&1 &
    BACKEND_PID=$!
    cd "$PROJECT_ROOT"
    
    if ! check_service "http://localhost:8080/api/health" "Backend"; then
        echo "❌ Backend failed to start. Check $LOG_DIR/backend/app.log"
        return 1
    fi
}

# 启动前端
start_frontend() {
    check_and_kill_port 3000
    
    echo "🎨 Starting Vite frontend server..."
    cd "$PROJECT_ROOT/client/src"
    npm run dev > "$LOG_DIR/frontend/app.log" 2>&1 &
    FRONTEND_PID=$!
    cd "$PROJECT_ROOT"
    
    if ! check_service "http://localhost:3000" "Frontend"; then
        echo "❌ Frontend failed to start. Check $LOG_DIR/frontend/app.log"
        return 1
    fi
}

# 启动Electron客户端
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
    ASR_PID=$!
    cd "$PROJECT_ROOT"
    
    if ! check_service "http://localhost:9000/healthz" "ASR Service"; then
        echo "❌ ASR service failed to start. Check $LOG_DIR/asr/app.log"
        return 1
    fi
}

# 启动TTS服务
start_tts() {
    # 从 application-local.yml 读取 tts-engine 配置
    local tts_engine="gpt-sovits"
    local config_file="$PROJECT_ROOT/backend/src/main/resources/application-local.yml"
    if [ -f "$config_file" ]; then
        local engine_line=$(grep -E '^\s*tts-engine:' "$config_file" 2>/dev/null | head -1)
        if [ -n "$engine_line" ]; then
            tts_engine=$(echo "$engine_line" | sed 's/.*tts-engine:[[:space:]]*//' | tr -d '"' | tr -d "'")
        fi
    fi

    echo "🔊 Starting TTS service (engine=$tts_engine)..."
    local tts_script="$PROJECT_ROOT/startup-scripts/start-tts.sh"
    if [ -f "$tts_script" ]; then
        bash "$tts_script" --engine "$tts_engine"
        # 根据引擎选择端口
        local tts_port=9880
        if [ "$tts_engine" = "mlx-audio" ]; then
            tts_port=9881
        fi
        TTS_PID=$(lsof -i :$tts_port -sTCP:LISTEN -t 2>/dev/null | head -1 || true)
        if [ -n "$TTS_PID" ]; then
            echo "✅ TTS service is running on port $tts_port (engine=$tts_engine, PID: $TTS_PID)"
        else
            echo "⚠️  TTS port $tts_port not yet listening, may still be loading..."
        fi
    else
        echo "⚠️  TTS start script not found: $tts_script"
        echo "   Skipping TTS service."
    fi
}

# 显示状态信息
show_status() {
    echo ""
    echo "🎯 AI-Chat Development Environment is ready!"
    echo "📱 Frontend: http://localhost:3000"
    echo "🔧 Backend: http://localhost:8080/api/health"
    echo "🎤 ASR: http://localhost:9000/healthz"
    echo "🔊 TTS: http://localhost:9880/9881 (engine-dependent)"
    echo "🖥️  Electron: Desktop app should appear"
    echo ""
    echo "📊 Unified Logs Location: $LOG_DIR/"
    echo "   Backend: $LOG_DIR/backend/app.log"
    echo "   Frontend: $LOG_DIR/frontend/app.log"
    echo "   Client: $LOG_DIR/client/app.log"
    echo "   ASR: $LOG_DIR/asr/app.log"
    echo "   TTS: $LOG_DIR/tts/gpt-sovits.log / $LOG_DIR/tts/mlx-audio/server.log"
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