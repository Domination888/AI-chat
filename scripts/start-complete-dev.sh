#!/bin/bash

echo "Starting AI-Chat Complete Development Environment..."

# 启动后端（后台运行）
echo "Starting backend..."
cd backend
./mvnw spring-boot:run &
BACKEND_PID=$!
cd ..

# 等待后端启动
sleep 10

# 启动前端开发服务器（后台运行）
echo "Starting frontend dev server..."
cd client/src
npm run dev &
FRONTEND_PID=$!
cd ../..

# 等待前端服务器启动
sleep 5

# 启动Electron（前台运行）
echo "Starting Electron client..."
cd client
npx electron .

# 清理后台进程
kill $BACKEND_PID $FRONTEND_PID 2>/dev/null