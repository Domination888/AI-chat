#!/bin/bash

# 启动Electron客户端

LOG_DIR="unified-logs"
PID_DIR="unified-logs/pids"

mkdir -p $LOG_DIR/client $PID_DIR

echo "🖥️  Starting Electron client..."
cd client
npx electron . > ../$LOG_DIR/client/app.log 2>&1 &
CLIENT_PID=$!
cd ..

# 保存PID
echo $CLIENT_PID > $PID_DIR/client.pid
echo $CLIENT_PID >> $PID_DIR/all_pids.txt

echo "✅ Electron client is starting..."
echo "📊 Logs: $LOG_DIR/client/app.log"

# 等待几秒钟确保启动
sleep 3