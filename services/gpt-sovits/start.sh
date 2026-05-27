#!/usr/bin/env bash
# =============================================================================
#  在 Mac (Apple M4) 上启动 GPT-SoVITS api_v2（端口 9880）
#
#  依赖（整合包已自带）：GPT-SoVITS/runtime/bin/python3
#
#  已经做好的配置（仅需做一次）：
#    GPT-SoVITS/GPT_SoVITS/configs/tts_infer.yaml
#      custom.version: v2ProPlus (黍是 v4 训出的 LoRA，但权重结构匹配 v2ProPlus 底模；
#                                  v4 BigVGAN 在 Mac CPU 上慢且 missing_keys 满屏)
#      custom.device:  mps        (强制使用 MPS 加速进行性能测试)
#      custom.is_half: false      (mps 下保持 false)
#    后端首次 TTS 时会自动 POST /set_gpt_weights & /set_sovits_weights 切到黍模型
#
#  运行（默认：已运行则自动重启）：
#    bash services/gpt-sovits/start.sh           # 已运行 → 杀掉旧进程后重启
#    bash services/gpt-sovits/start.sh --no-restart   # 已运行 → 不动它，直接退出
#    bash services/gpt-sovits/start.sh stop      # 仅停止，不启动
#  日志：unified-logs/tts/gpt-sovits.log    手动停止：kill $(awk '$1=="gpt-sovits"{print $2}' unified-logs/pids/pids.txt)
# =============================================================================

set -euo pipefail

# ---------- 参数解析 ----------
MODE="restart"   # 默认：已运行就重启
case "${1:-}" in
  --no-restart|-n)  MODE="no-restart" ;;
  stop)             MODE="stop" ;;
  ""|--restart)     MODE="restart" ;;
  -h|--help)
    sed -n '1,30p' "$0"
    exit 0
    ;;
  *)
    echo "❌ 未知参数：$1（支持：无参数 / --no-restart / stop / --help）" >&2
    exit 2
    ;;
esac

# 项目根（脚本位于 <root>/services/gpt-sovits/ 下）
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
GPT_SOVITS_DIR="$SCRIPT_DIR/GPT-SoVITS"
LOG_DIR="$PROJECT_ROOT/unified-logs/tts"
LOG="$LOG_DIR/gpt-sovits.log"
PID_DIR="$PROJECT_ROOT/unified-logs/pids"
PID_FILE="$PID_DIR/pids.txt"

RUNTIME_PY="$GPT_SOVITS_DIR/runtime/bin/python3"
CONFIG_REL="GPT_SoVITS/configs/tts_infer.yaml"

FORCE_DEVICE="${GPT_SOVITS_DEVICE:-mps}"
FORCE_VERSION="${GPT_SOVITS_FORCE_VERSION:-v4}"

# ---------- 工具函数：停掉旧实例（端口占用 + PID 文件双兜底） ----------
remove_pid_entry() {
  if [ -f "$PID_FILE" ]; then
    grep -v "^gpt-sovits " "$PID_FILE" > "$PID_FILE.tmp" || true
    mv "$PID_FILE.tmp" "$PID_FILE"
  fi
}

stop_existing() {
  local stopped=0

  # 1) 优先按端口找
  local port_pids
  port_pids="$(lsof -i :9880 -sTCP:LISTEN -t 2>/dev/null || true)"
  if [ -n "$port_pids" ]; then
    echo "🛑 端口 9880 被占用，停止进程：$port_pids"
    # shellcheck disable=SC2086
    kill $port_pids 2>/dev/null || true
    stopped=1
  fi

  # 2) pids.txt 兜底（可能进程没监听但还活着）
  if [ -f "$PID_FILE" ]; then
    local file_pid
    file_pid="$(awk '$1=="gpt-sovits"{print $2}' "$PID_FILE" | tail -1)"
    if [ -n "$file_pid" ] && kill -0 "$file_pid" 2>/dev/null; then
      echo "🛑 停止 PID 文件中的进程：$file_pid"
      kill "$file_pid" 2>/dev/null || true
      stopped=1
    elif [ -n "$file_pid" ]; then
      remove_pid_entry
    fi
  fi

  if [ "$stopped" -eq 0 ]; then
    return 0
  fi

  # 等端口真正释放（最多 10s），不放心 SIGTERM 就升级 SIGKILL
  for _ in $(seq 1 10); do
    if ! lsof -i :9880 -sTCP:LISTEN -t >/dev/null 2>&1; then
      echo "✅ 旧实例已停止"
      remove_pid_entry
      return 0
    fi
    sleep 1
  done

  echo "⚠️  10s 内端口仍被占用，发送 SIGKILL"
  port_pids="$(lsof -i :9880 -sTCP:LISTEN -t 2>/dev/null || true)"
  if [ -n "$port_pids" ]; then
    # shellcheck disable=SC2086
    kill -9 $port_pids 2>/dev/null || true
  fi
  sleep 1
  remove_pid_entry
}

# ---------- stop 子命令：仅停止 ----------
if [ "$MODE" = "stop" ]; then
  stop_existing
  exit 0
fi

# ---------- 启动前置检查 ----------
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

# ---------- 处理已有实例 ----------
if lsof -i :9880 -sTCP:LISTEN -t >/dev/null 2>&1; then
  if [ "$MODE" = "no-restart" ]; then
    echo "ℹ️  9880 端口已有进程，按 --no-restart 模式直接退出。"
    lsof -i :9880 -sTCP:LISTEN
    exit 0
  fi
  echo "🔄 检测到 GPT-SoVITS 已在运行，准备重启……"
  stop_existing
fi

cd "$GPT_SOVITS_DIR"
mkdir -p "$LOG_DIR"
mkdir -p "$PID_DIR"
# 和 go-api.command 保持一致：让内置 runtime 的二进制 / 动态库优先
export PATH="$GPT_SOVITS_DIR/runtime/bin:$PATH"
export DYLD_LIBRARY_PATH="$GPT_SOVITS_DIR/runtime/lib:${DYLD_LIBRARY_PATH:-}"

echo "🚀 启动 GPT-SoVITS api_v2"
echo "   python : $RUNTIME_PY"
echo "   config : $GPT_SOVITS_DIR/$CONFIG_REL  (custom.version=$FORCE_VERSION, device=$FORCE_DEVICE)"
echo "   log    : $LOG"

nohup "$RUNTIME_PY" api_v2.py -a 127.0.0.1 -p 9880 -c "$CONFIG_REL" \
  --device "$FORCE_DEVICE" --force-version "$FORCE_VERSION" \
  > "$LOG" 2>&1 &
PID=$!
if [ -f "$PID_FILE" ]; then
  grep -v "^gpt-sovits " "$PID_FILE" > "$PID_FILE.tmp" || true
  mv "$PID_FILE.tmp" "$PID_FILE"
fi
echo "gpt-sovits $PID" >> "$PID_FILE"
echo "   pid    : $PID  (写入 $PID_FILE)"

# 轮询端口最多 30s（首次加载 bert/hubert 会慢）
for i in $(seq 1 30); do
  if lsof -i :9880 -sTCP:LISTEN -t >/dev/null 2>&1; then
    echo "🎉 已就绪：http://127.0.0.1:9880"
    echo "   健康检查：curl -sS 'http://127.0.0.1:9880/control?command=ping' || echo OK"
    echo "   停止服务：kill \$(awk '\$1==\"gpt-sovits\"{print \$2}' $PID_FILE)"
    exit 0
  fi
  sleep 1
done

echo "❌ 30s 内未监听 9880，请看日志："
tail -60 "$LOG"
exit 1