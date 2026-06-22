# TODO

本文件只记录当前仍有意义的待办。已完成的历史任务不再保留在这里。

## 高优先级

- 记忆系统回归：Memos 写入只包含 user 消息，检索结果正确注入 prompt，Memos 不可用时 fallback 到 Redis RAG。
- 联网搜索回归：开发期 Docker SearXNG、打包版 SearXNG、SearXNG MCP 工具调用三段都要测。
- 全链路延迟排查：LLM 首 token、TTS 首包、ASR 耗时、RAG/Memos 查询耗时分别看 `unified-logs/backend/latency.log`。

## 中期优化

- 黍人格继续打磨：角色卡、memory_cards、lore 去重和质量回归。
- 检查 `backend/src/main/resources/personas/shu/lore` 与历史 raw 数据是否重复。
- 打包后性能测试

## 长期方向

- 拓展 Skill / MCP 工具体系。
- 训练或替换更适合角色对话的 TTS 音色。
- 优化kv cache

## 回归清单

- 用户在 AI 回复/TTS 播放时发送新消息，旧回复和音频应被打断。
- 主动搭话开关、空闲时间、提示词保存后应立即生效。
