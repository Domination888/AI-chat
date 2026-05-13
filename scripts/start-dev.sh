#!/bin/bash

echo "Starting AI-Chat Development Environment..."

# 启动后端（后台运行）
echo "Starting backend..."
cd backend
./mvnw spring-boot:run &
BACKEND_PID=$!
cd ..

# 等待后端启动
sleep 10

# 启动Electron客户端
echo "Starting Electron client..."
cd client
npm run dev &
CLIENT_PID=$!
cd ..

echo "Development environment started!"
echo "Backend PID: $BACKEND_PID"
echo "Client PID: $CLIENT_PID"
echo ""
echo "Press Ctrl+C to stop all services"

# 等待用户中断
trap "kill $BACKEND_PID $CLIENT_PID; exit" INT
wait