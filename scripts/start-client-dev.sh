#!/bin/bash

echo "Starting AI-Chat Client Development Environment..."

cd client

# 启动Vite开发服务器（后台运行）
echo "Starting Vite dev server..."
npm run dev:vite &
VITE_PID=$!

# 等待Vite服务器启动
sleep 5

# 启动Electron
echo "Starting Electron..."
npm run dev:electron &
ELECTRON_PID=$!

echo "Client development environment started!"
echo "Vite PID: $VITE_PID"
echo "Electron PID: $ELECTRON_PID"
echo ""
echo "Press Ctrl+C to stop all services"

# 等待用户中断
trap "kill $VITE_PID $ELECTRON_PID; exit" INT
wait