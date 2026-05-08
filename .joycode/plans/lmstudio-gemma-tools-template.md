# LM Studio + gemma-4-31b-it 的 Tool Use 修复

## 问题根因（已验证）

- 你在 LM Studio 加载的是 `gemma-4-31b-it`（Gemma 4 官方没发，基本是第三方魔改预览版）
- `/api/v0/models/gemma-4-31b-it` 返回 `capabilities: ["tool_use"]`，
  说明 LM Studio 把它当 "Native tool use" —— 直接走 GGUF 内置 jinja 模板渲染 tools
- **但 Gemma 官方 jinja 模板本身没有 tools 分支**，魔改作者塞进去的分支写错了，
  一旦请求带 `tools`（哪怕 `[]` 或一个最简单的函数），就会抛：
  `Cannot call something that is not a function: got UndefinedValue`
- 复现：任何 OpenAI 标准 `tools` 请求都必炸，与我们的 Java 代码 / langchain4j 无关

## 修复 A（推荐）：换模板，让 LM Studio 走 Default tool 格式

### 操作步骤

1. 打开 LM Studio，左侧 **My Models**
2. 找到 `gemma-4-31b-it`，点右侧的 **⚙ 齿轮**（Edit Model Default Config）
3. 展开 **Prompt Template**，把下面这段 Jinja **整段粘贴覆盖**原模板：

```jinja
{{ bos_token }}
{%- if messages[0]['role'] == 'system' -%}
{{ '<start_of_turn>user\n' + messages[0]['content'] }}
{%- if tools is defined and tools -%}
{{ '\n\n# Tools\n你可以调用以下工具来帮助用户。以 JSON 形式把函数签名写在 <tools></tools> 之间：\n<tools>\n' }}
{%- for t in tools -%}
{{ t | tojson }}{{ '\n' }}
{%- endfor -%}
{{ '</tools>\n\n当需要调用工具时，仅输出如下 JSON（不要其它文字）：\n<tool_call>{"name":"<function-name>","arguments":<args-json-object>}</tool_call>' }}
{%- endif -%}
{{ '<end_of_turn>\n' }}
{%- set loop_messages = messages[1:] -%}
{%- else -%}
{%- set loop_messages = messages -%}
{%- endif -%}
{%- for message in loop_messages -%}
{%- if message['role'] == 'user' -%}
{{ '<start_of_turn>user\n' + message['content'] + '<end_of_turn>\n' }}
{%- elif message['role'] == 'assistant' -%}
{{ '<start_of_turn>model\n' + (message['content'] or '') }}
{%- if message.tool_calls -%}
{%- for tc in message.tool_calls -%}
{{ '\n<tool_call>' ~ ({'name': tc.function.name, 'arguments': (tc.function.arguments if tc.function.arguments is string else tc.function.arguments | tojson)} | tojson) ~ '</tool_call>' }}
{%- endfor -%}
{%- endif -%}
{{ '<end_of_turn>\n' }}
{%- elif message['role'] == 'tool' -%}
{{ '<start_of_turn>user\n<tool_response>' + message['content'] + '</tool_response><end_of_turn>\n' }}
{%- endif -%}
{%- endfor -%}
{%- if add_generation_prompt -%}
{{ '<start_of_turn>model\n' }}
{%- endif -%}
```

4. 保存，**Eject 重新 Load 模型**让模板生效
5. 验证：

```bash
curl -sS -X POST http://192.168.124.2:1234/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{
    "model":"gemma-4-31b-it",
    "messages":[
      {"role":"system","content":"You are helpful."},
      {"role":"user","content":"What is 3 plus 5?"}
    ],
    "tools":[{"type":"function","function":{"name":"add","description":"Add","parameters":{"type":"object","properties":{"a":{"type":"number"},"b":{"type":"number"}},"required":["a","b"]}}}],
    "stream":false, "max_tokens":128
  }'
```

期望：正常返回 `tool_calls` 数组（或者 content 里的 `<tool_call>...</tool_call>`，
LM Studio 会自动解析成 `tool_calls` 字段）。

## 修复 B（更彻底）：换一个官方支持 tool use 的模型

显存 16GB，推荐：
- `Qwen2.5-32B-Instruct` Q4_K_M（约 19GB，不一定能塞下）
- `Qwen2.5-14B-Instruct` Q4_K_M（约 9GB，稳）
- `Meta-Llama-3.1-8B-Instruct` Q6_K（约 6GB，速度快）

这些在 LM Studio 里会显示 🔨 hammer badge，jinja 模板原生带 tools 分支，不用改配置。

## 代码侧保留的开关

- `ChatRequest.tools` 字段（默认 true）：调用方可显式关掉工具下发
- 语音通道 `/api/audio/chat-stream` 保持 `tools=false`（降延迟，无业务场景需要）
- 文本聊天 `/api/chat`：照常下发 tools，模板修好后就能用