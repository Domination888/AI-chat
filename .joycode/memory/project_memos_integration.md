---
name: memos-integration
description: Memos 写入策略 —— 只推 user 单条避免 assistant 污染 UserMemory 桶
type: project
---

Memos 部署在 http://localhost:8000，作为长期记忆模块。

**Why**: Memos 内部 MemReader (fine 模式) 用 LLM 从 messages 抽事实。同时推 user + assistant 时，Memos LLM 会把 assistant 内容（包括幻觉）误归到 UserMemory 桶。已实测被污染。即便 fast 模式按 role 切分理论无问题，mixture 模式下 fine 路径仍会跑。

**How to apply**:
- 写入只推 user 单条：`MemosClient.addUserMessage()` 发 `messages: [{role:"user", content: 用户原话}]`
- 永远不推 assistant；`addConversationMemory` 已删除
- 想保留"角色日记"需走独立 cube_id，文本先用 Gemma 加工成已成型陈述再以 role=user 喂入
- 搜索 `searchStructured()` 按 metadata.memory_type 分组返回，ChatServiceImpl 分段注入 prompt
- 当前 cube_id: b32d0977-435d-4828-a86f-4f47f8b55bca; user_id: 8736b16e-1d20-4163-980b-a5063c3facdc
- Memos 挂掉自动 fallback 到 Redis RAG
- 历史脏数据需手动到 Neo4j 面板清理