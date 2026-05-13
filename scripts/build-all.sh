#!/bin/bash

echo "Building AI-Chat Project..."

# 构建后端
echo "Building backend..."
cd backend
./mvnw clean package -DskipTests
cd ..

# 构建客户端
echo "Building client..."
cd client
npm install
npm run build
cd ..

echo "Build completed!"
echo "Backend JAR: backend/target/AI-Chat-0.0.1-SNAPSHOT.jar"
echo "Client dist: client/dist/"