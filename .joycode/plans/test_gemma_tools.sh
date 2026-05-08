#!/bin/bash
# 测试当前 LM Studio 加载的模型对不同 tools 请求形状的反应
# 用法：MODEL=qwen3.5-9b-ud bash .joycode/plans/test_gemma_tools.sh
BASE=http://192.168.124.2:1234/v1/chat/completions
MODEL="${MODEL:-qwen3.5-9b-ud}"

run() {
  local title="$1"; shift
  local body="$1"
  echo "========== $title =========="
  resp=$(curl -sS -X POST "$BASE" -H 'Content-Type: application/json' -d "$body" --max-time 90)
  if echo "$resp" | head -c 1 | grep -q '{'; then
    echo "$resp" | python3 -c 'import sys,json
try:
  d=json.load(sys.stdin)
  if "error" in d:
    m=d["error"]
    print("❌ ERROR:", m if isinstance(m,str) else m.get("message",m)[:200])
  else:
    c=d["choices"][0]["message"]
    print("✅ content:", (c.get("content") or "")[:120])
    print("   tool_calls:", c.get("tool_calls"))
except Exception as e:
  print("parse fail:", e)'
  else
    echo "$resp" | head -c 300
  fi
  echo
}

TOOL='{"type":"function","function":{"name":"add","description":"Add two numbers","parameters":{"type":"object","properties":{"a":{"type":"number"},"b":{"type":"number"}},"required":["a","b"]}}}'

run "1) no tools (baseline)" '{
  "model":"'$MODEL'",
  "messages":[{"role":"user","content":"3 plus 5"}],
  "stream":false, "max_tokens":32
}'

run "2) tools=[] empty array" '{
  "model":"'$MODEL'",
  "messages":[{"role":"user","content":"3 plus 5"}],
  "tools":[],
  "stream":false, "max_tokens":32
}'

run "3) tools=[add]" "{
  \"model\":\"'$MODEL'\",
  \"messages\":[{\"role\":\"user\",\"content\":\"3 plus 5, call add\"}],
  \"tools\":[$TOOL],
  \"stream\":false, \"max_tokens\":64
}"

run "4) tools + tool_choice=auto" "{
  \"model\":\"'$MODEL'\",
  \"messages\":[{\"role\":\"user\",\"content\":\"3 plus 5, call add\"}],
  \"tools\":[$TOOL],
  \"tool_choice\":\"auto\",
  \"stream\":false, \"max_tokens\":64
}"

run "5) tools + tool_choice=none" "{
  \"model\":\"'$MODEL'\",
  \"messages\":[{\"role\":\"user\",\"content\":\"3 plus 5\"}],
  \"tools\":[$TOOL],
  \"tool_choice\":\"none\",
  \"stream\":false, \"max_tokens\":64
}"

run "6) tools + system msg" "{
  \"model\":\"'$MODEL'\",
  \"messages\":[{\"role\":\"system\",\"content\":\"You are a helpful assistant with access to tools.\"},{\"role\":\"user\",\"content\":\"3 plus 5, call add\"}],
  \"tools\":[$TOOL],
  \"stream\":false, \"max_tokens\":64
}"

run "7) strict=true function (OpenAI new)" '{
  "model":"'$MODEL'",
  "messages":[{"role":"user","content":"3 plus 5"}],
  "tools":[{"type":"function","function":{"name":"add","description":"Add","strict":true,"parameters":{"type":"object","additionalProperties":false,"properties":{"a":{"type":"number"},"b":{"type":"number"}},"required":["a","b"]}}}],
  "stream":false, "max_tokens":64
}'