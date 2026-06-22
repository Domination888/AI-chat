#!/bin/bash

# AI-Chat 开发环境初始化脚本
# 一次性安装所有依赖

set -e

echo "🔧 Setting up AI-Chat Development Environment..."

# 安装后端依赖
echo "📦 Installing backend dependencies (Maven)..."
cd backend
./mvnw dependency:resolve
cd ..

# 安装前端依赖 (client/src 下有独立的 package.json)
echo "📦 Installing frontend dependencies (client/src)..."
cd client/src
npm install
cd ../..

# 安装 Electron 客户端依赖 (client 根目录)
echo "📦 Installing Electron client dependencies (client)..."
cd client
npm install
cd ..

# 检查 ASR 依赖
echo "📦 Checking ASR dependencies..."
if [ -f "services/sense-voice/requirements.txt" ]; then
    pip3 install -r services/sense-voice/requirements.txt 2>/dev/null || \
        echo "⚠️  ASR Python 依赖安装失败，可能需要手动安装"
fi

echo ""
echo "✅ Setup completed!"
echo ""
echo "To start development:"
echo "  ./startup-scripts/start-all.sh           # 启动所有服务"
echo "  ./startup-scripts/stop-all.sh            # 停止所有服务"
echo "  docs/scripts.md                          # 查看脚本说明"
