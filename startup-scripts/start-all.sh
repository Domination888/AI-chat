#!/bin/bash

# AI-Chat 完整开发环境启动脚本
# 包含：后端、前端、客户端、ASR、TTS

set -e

LOG_DIR="unified-logs"
PID_DIR="unified-logs/pids"

# 创建日志目录
mkdir -p $LOG_DIR/{backend,frontend,client,electron,asr,tts,prompt}
mkdir -p $PID_DIR

echo "🚀 Starting AI-Chat Complete Development Environment..."

# 清理函数
cleanup() {
    echo ""
    echo "🛑 Stopping all services..."
    if [ -f "$PID_DIR/all_pids.txt" ]; then
        cat "$PID_DIR/all_pids.txt" | xargs kill -9 2>/dev/null || true
        rm -f "$PID_DIR/all_pids.txt"
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
    cd backend
    ./mvnw spring-boot:run > ../$LOG_DIR/backend/app.log 2>&1 &
    BACKEND_PID=$!
    cd ..
    echo $BACKEND_PID >> $PID_DIR/all_pids.txt
    
    if ! check_service "http://localhost:8080/api/health" "Backend"; then
        echo "❌ Backend failed to start. Check $LOG_DIR/backend/app.log"
        return 1
    fi
}

# 启动前端
start_frontend() {
    check_and_kill_port 3000
    
    echo "🎨 Starting Vite frontend server..."
    cd client/src
    npm run dev > ../../$LOG_DIR/frontend/app.log 2>&1 &
    FRONTEND_PID=$!
    cd ../..
    echo $FRONTEND_PID >> $PID_DIR/all_pids.txt
    
    if ! check_service "http://localhost:3000" "Frontend"; then
        echo "❌ Frontend failed to start. Check $LOG_DIR/frontend/app.log"
        return 1
    fi
}

# 启动Electron客户端
start_client() {
    echo "🖥️  Starting Electron client..."
    cd client
    npx electron . > ../$LOG_DIR/client/app.log 2>&1 &
    ELECTRON_PID=$!
    cd ..
    echo $ELECTRON_PID >> $PID_DIR/all_pids.txt
    echo "✅ Electron client is starting..."
}

# 启动ASR服务
start_asr() {
    check_and_kill_port 9000
    
    echo "🎤 Starting SenseVoice ASR service..."
    cd services/sense-voice
    python server.py > ../../$LOG_DIR/asr/app.log 2>&1 &
    ASR_PID=$!
    cd ../..
    echo $ASR_PID >> $PID_DIR/all_pids.txt
    
    if ! check_service "http://localhost:9000/healthz" "ASR Service"; then
        echo "❌ ASR service failed to start. Check $LOG_DIR/asr/app.log"
        return 1
    fi
}

# 启动TTS服务
start_tts() {
    check_and_kill_port 9880
    
    echo "🔊 Starting GPT-SoVITS TTS service..."
    cd services/gpt-sovits
    # 这里需要根据实际TTS启动命令调整
    if [ -f "start.sh" ]; then
        bash start.sh > ../../$LOG_DIR/tts/app.log 2>&1 &
        TTS_PID=$!
        echo $TTS_PID >> ../../$PID_DIR/all_pids.txt
        echo "✅ TTS service is starting..."
    else
        echo "⚠️  TTS start script not found. Skipping TTS service."
    fi
    cd ../..
}

# 显示状态信息
show_status() {
    echo ""
    echo "🎯 AI-Chat Development Environment is ready!"
    echo "📱 Frontend: http://localhost:3000"
    echo "🔧 Backend: http://localhost:8080/api/health"
    echo "🎤 ASR: http://localhost:9000/healthz"
    echo "🔊 TTS: http://localhost:9880 (if configured)"
    echo "🖥️  Electron: Desktop app should appear"
    echo ""
    echo "📊 Unified Logs Location: $LOG_DIR/"
    echo "   Backend: $LOG_DIR/backend/app.log"
    echo "   Frontend: $LOG_DIR/frontend/app.log"
    echo "   Client: $LOG_DIR/client/app.log"
    echo "   ASR: $LOG_DIR/asr/app.log"
    echo "   TTS: $LOG_DIR/tts/app.log"
    echo ""
    echo "🛑 To stop all services: Press Ctrl+C or run kill \$(cat $PID_DIR/all_pids.txt)"
    echo "   Or use: ./startup-scripts/stop-all.sh"
}

# 保存PID
save_pids() {
    echo "Backend PID: $BACKEND_PID" > $PID_DIR/service_pids.txt
    echo "Frontend PID: $FRONTEND_PID" >> $PID_DIR/service_pids.txt
    echo "Electron PID: $ELECTRON_PID" >> $PID_DIR/service_pids.txt
    echo "ASR PID: $ASR_PID" >> $PID_DIR/service_pids.txt
    [ -n "$TTS_PID" ] && echo "TTS PID: $TTS_PID" >> $PID_DIR/service_pids.txt
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