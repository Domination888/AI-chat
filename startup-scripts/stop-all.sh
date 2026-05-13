#!/bin/bash

# AI-Chat 完整开发环境停止脚本

PID_DIR="unified-logs/pids"

echo "🛑 Stopping AI-Chat Development Environment..."

# 检查PID文件是否存在
if [ ! -f "$PID_DIR/all_pids.txt" ]; then
    echo "⚠️  No PID file found. Attempting to kill by ports..."
    
    # 按端口杀死进程
    ports=(8080 3000 9000 9880)
    for port in "${ports[@]}"; do
        if lsof -ti:$port >/dev/null; then
            echo "🔍 Killing processes on port $port..."
            lsof -ti:$port | xargs kill -9 2>/dev/null || true
        fi
    done
    
    # 杀死可能的Electron进程
    pkill -f "electron" 2>/dev/null || true
    pkill -f "electron.app" 2>/dev/null || true
    
    echo "✅ All services stopped by port."
else
    # 从PID文件杀死进程
    echo "🔍 Killing processes from PID file..."
    cat "$PID_DIR/all_pids.txt" | xargs kill -9 2>/dev/null || true
    rm -f "$PID_DIR/all_pids.txt"
    rm -f "$PID_DIR/service_pids.txt"
    echo "✅ All services stopped by PID."
fi

echo "🧹 Cleaning up log files..."
# 可选：清理日志文件
# rm -f unified-logs/*/*.log

echo "✅ AI-Chat Development Environment has been stopped."