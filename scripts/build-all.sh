#!/bin/bash

# AI-Chat 全量构建脚本
# 先构建后端 JAR，再构建前端 dist

set -e

echo "🏗️  Building AI-Chat Project..."

# 构建后端
echo "📦 Building backend JAR..."
cd backend
./mvnw clean package -DskipTests
cd ..

# 构建前端 (Vite → client/dist/)
echo "📦 Building client dist..."
cd client
npm run build
cd ..

echo ""
echo "✅ Build completed!"
echo "   Backend JAR: backend/target/AI-Chat-0.0.1-SNAPSHOT.jar"
echo "   Client dist: client/dist/"