# AI-Chat 启动脚本

这个目录包含了AI-Chat项目的所有启动和停止脚本。

## 脚本说明

### 1. 完整环境启动
```bash
# 启动所有服务（后端、前端、客户端、ASR、TTS）
./startup-scripts/start-all.sh

# 停止所有服务
./startup-scripts/stop-all.sh
```

### 2. 单独服务启动
```bash
# 启动特定服务
./startup-scripts/start-backend.sh    # 启动后端服务
./startup-scripts/start-asr.sh        # 启动ASR服务
./startup-scripts/start-frontend.sh   # 启动前端服务
./startup-scripts/start-client.sh     # 启动客户端
./startup-scripts/start-tts.sh        # 启动TTS服务
```

## 日志结构

所有服务的日志现在都统一放在 `unified-logs/` 目录下：

```
unified-logs/
├── backend/
│   └── app.log
├── frontend/
│   └── app.log
├── client/
│   └── app.log
├── asr/
│   └── app.log
├── tts/
│   └── app.log
├── prompt/
│   └── app.log
└── pids/
    ├── all_pids.txt       # 所有进程ID
    ├── backend.pid        # 后端进程ID
    ├── asr.pid           # ASR进程ID
    └── service_pids.txt   # 服务PID详情
```

## 使用说明

1. **启动完整环境**：
   ```bash
   ./startup-scripts/start-all.sh
   ```

2. **启动特定服务**：
   ```bash
   ./startup-scripts/start-backend.sh
   ./startup-scripts/start-asr.sh
   ```

3. **停止服务**：
   ```bash
   # 停止所有服务
   ./startup-scripts/stop-all.sh
   
   # 或者按Ctrl+C停止start-all.sh
   ```

4. **查看日志**：
   ```bash
   tail -f unified-logs/backend/app.log
   tail -f unified-logs/asr/app.log
   ```

## 端口说明

- **后端**: 8080
- **前端**: 3000
- **ASR**: 9000
- **TTS**: 9880

## 注意事项

- 所有脚本都会自动清理端口占用
- 服务启动后会自动检查健康状态
- PID文件用于进程管理
- 日志文件按服务分类存储