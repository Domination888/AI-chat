# SenseVoice ASR 服务

这是一个基于SenseVoice的ASR（自动语音识别）服务，提供OpenAI风格的API接口。

## 服务说明

- 监听地址: `127.0.0.1:9000`
- 主要API:
  - `POST /v1/audio/transcriptions` - 语音识别（支持文件上传）
  - `GET /healthz` - 健康检查

## 依赖安装

```bash
# 创建虚拟环境
python -m venv venv
source venv/bin/activate  # Linux/Mac
# 或 venv\Scripts\activate  # Windows

# 安装依赖
pip install -r requirements.txt
```

## 模型文件

模型文件会自动下载到用户目录：
- SenseVoiceSmall: `~/.cache/modelscope/hub/models/iic/SenseVoiceSmall`
- FSMN-VAD: `~/.cache/modelscope/hub/models/iic/speech_fsmn_vad_zh-cn-16k-common-pytorch`

## 启动服务

```bash
# 直接运行
python server.py

# 或使用uvicorn
uvicorn server:app --host 127.0.0.1 --port 9000
```

## API使用示例

```bash
# 健康检查
curl http://127.0.0.1:9000/healthz

# 语音识别
curl -X POST http://127.0.0.1:9000/v1/audio/transcriptions \
  -F "file=@sample.wav" \
  -F "language=auto"
```

## 响应格式

```json
{
  "text": "识别出的文本",
  "language": "zh",
  "emotion": "HAPPY",
  "event": "Speech",
  "raw": "<|zh|><|HAPPY|><|Speech|>识别出的文本"
}
```