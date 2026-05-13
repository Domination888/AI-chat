#!/bin/bash

echo "🚀 Starting AI-Chat Development Environment (Reliable Mode)..."

# 清理之前的进程
./stop-dev.sh

# 创建日志目录
mkdir -p logs

# 启动后端
echo "📦 Starting backend..."
cd backend
./mvnw spring-boot:run > ../logs/backend.log 2>&1 &
BACKEND_PID=$!
cd ..

# 等待后端启动
echo "⏳ Waiting for backend to start..."
for i in {1..30}; do
    if curl -s http://localhost:8080/api/health > /dev/null; then
        echo "✅ Backend is running!"
        break
    fi
    if [ $i -eq 30 ]; then
        echo "❌ Backend failed to start. Check logs/backend.log"
        exit 1
    fi
    sleep 1
done

# 启动前端
echo "🎨 Starting frontend..."
cd client/src
npm run dev > ../../logs/frontend.log 2>&1 &
FRONTEND_PID=$!
cd ../..

# 等待前端启动
echo "⏳ Waiting for frontend to start..."
for i in {1..30}; do
    if curl -s http://localhost:3000 > /dev/null; then
        echo "✅ Frontend is running!"
        break
    fi
    if [ $i -eq 30 ]; then
        echo "❌ Frontend failed to start. Check logs/frontend.log"
        exit 1
    fi
    sleep 1
done

# 启动Electron
echo "🖥️ Starting Electron..."
cd client
npx electron . > ../logs/electron.log 2>&1 &
ELECTRON_PID=$!
cd ..

echo "✅ All services started successfully!"
echo "🎯 Development environment is ready!"

# 保存PID
echo $BACKEND_PID > logs/backend.pid
echo $FRONTEND_PID > logs/frontend.pid
echo $ELECTRON_PID > logs/electron.pid

# 显示状态
echo ""
echo "📊 Services Status:"
echo "   Backend: http://localhost:8080/api/health"
echo "   Frontend: http://localhost:3000"
echo "   Electron: Desktop app should appear"
echo ""
echo "📁 Logs:"
echo "   Backend: logs/backend.log"
echo "   Frontend: logs/frontend.log"
echo "   Electron: logs/electron.log"
echo ""
echo "🛑 To stop: ./stop-dev.sh"