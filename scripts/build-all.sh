#!/bin/bash

# AI-Chat 全量构建脚本
# 先构建后端 JAR，再构建前端 dist

set -e

echo "🏗️  Building AI-Chat Project..."

# 构建本地 MCP 服务器（stdio jar）：SearXNG 联网搜索 + Prime 示例
echo "📦 Building local MCP server jars..."
./backend/mvnw -f services/searxng-mcp-server/pom.xml clean package -DskipTests
./backend/mvnw -f services/prime-mcp-server/pom.xml clean package -DskipTests

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