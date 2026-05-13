#!/bin/bash

echo "Setting up AI-Chat Development Environment..."

# 安装后端依赖
echo "Installing backend dependencies..."
cd backend
./mvnw dependency:resolve
cd ..

# 安装客户端依赖
echo "Installing client dependencies..."
cd client/src
npm install
cd ../..

# 安装Electron依赖
echo "Installing Electron dependencies..."
cd client
npm install electron --save-dev
cd ..

echo "Setup completed!"
echo "To start development:"
echo "1. Start backend: cd backend && ./mvnw spring-boot:run"
echo "2. Start frontend: cd client/src && npm run dev"
echo "3. Start Electron: cd client && npx electron ."