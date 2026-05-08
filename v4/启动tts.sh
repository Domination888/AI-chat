#!/usr/bin/env bash
# =============================================================================
#  在 Mac (Apple M4) 上启动 GPT-SoVITS api_v2（端口 9880）
#
#  依赖（整合包已自带）：GPT-SoVITS/runtime/bin/python3
#  和 go-api.command 原理一致，只是启 api_v2.py 以支持流式 / 权重切换
#
#  已经做好的配置（仅需做一次）：
#    GPT-SoVITS/GPT_SoVITS/configs/tts_infer.yaml
#      custom.device: mps        (Apple Metal GPU 加速，Mac 没 CUDA)
#      custom.is_half: false     (mps 的 float16 兼容性差)
#    后端首次 TTS 时会自动 POST /set_gpt_weights & /set_sovits_weights 切到 v4/黍 模型
#
#  运行：
#    bash v4/启动tts.sh
#  日志：v4/tts.log    停止：kill $(cat v4/tts.pid)
# =============================================================================

set -euo pipefail

# 项目根（脚本位于 <root>/v4/ 下）
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
GPT_SOVITS_DIR="$ROOT_DIR/GPT-SoVITS"
LOG="$ROOT_DIR/v4/tts.log"
PID_FILE="$ROOT_DIR/v4/tts.pid"

RUNTIME_PY="$GPT_SOVITS_DIR/runtime/bin/python3"
CONFIG_REL="GPT_SoVITS/configs/tts_infer.yaml"

if [ ! -x "$RUNTIME_PY" ]; then
  echo "❌ 找不到整合包内置 python: $RUNTIME_PY" >&2
  echo "   请确认 GPT-SoVITS 整合包已解压完毕（go-api.command 正常才能跑）。" >&2
  exit 1
fi
if [ ! -f "$GPT_SOVITS_DIR/$CONFIG_REL" ]; then
  echo "❌ 找不到配置: $GPT_SOVITS_DIR/$CONFIG_REL" >&2
  exit 1
fi
if [ ! -f "$GPT_SOVITS_DIR/api_v2.py" ]; then
  echo "❌ 找不到 api_v2.py" >&2
  exit 1
fi

# 端口占用则短路
if lsof -i :9880 -sTCP:LISTEN -t >/dev/null 2>&1; then
  echo "ℹ️  9880 端口已有进程，假定 GPT-SoVITS 已启动。"
  lsof -i :9880 -sTCP:LISTEN
  exit 0
fi

cd "$GPT_SOVITS_DIR"
# 和 go-api.command 保持一致：让内置 runtime 的二进制 / 动态库优先
export PATH="$GPT_SOVITS_DIR/runtime/bin:$PATH"
export DYLD_LIBRARY_PATH="$GPT_SOVITS_DIR/runtime/lib:${DYLD_LIBRARY_PATH:-}"

echo "🚀 启动 GPT-SoVITS api_v2"
echo "   python : $RUNTIME_PY"
echo "   config : $GPT_SOVITS_DIR/$CONFIG_REL  (custom.device=mps)"
echo "   log    : $LOG"

nohup "$RUNTIME_PY" api_v2.py -a 127.0.0.1 -p 9880 -c "$CONFIG_REL" > "$LOG" 2>&1 &
PID=$!
echo "$PID" > "$PID_FILE"
echo "   pid    : $PID  (写入 $PID_FILE)"

# 轮询端口最多 30s（mps 首次加载 bert/hubert 会慢）
for i in $(seq 1 30); do
  if lsof -i :9880 -sTCP:LISTEN -t >/dev/null 2>&1; then
    echo "🎉 已就绪：http://127.0.0.1:9880"
    echo "   健康检查：curl -sS 'http://127.0.0.1:9880/control?command=ping' || echo OK"
    echo "   停止服务：kill \$(cat $PID_FILE)"
    exit 0
  fi
  sleep 1
done

echo "❌ 30s 内未监听 9880，请看日志："
tail -60 "$LOG"
exit 1