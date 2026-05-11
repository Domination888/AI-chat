# PLAN-008 · RAG / Tools / Search 默认开关 与 排查手册

> 关联：[`PLAN-001`](.joycode/plans/PLAN-001-ai-chat-role-play-refactor.md:1)、[`PLAN-004`](.joycode/plans/PLAN-004-neuro-orchestration-interrupt-tools.md:1)
> 涉及代码：[`ChatRequest`](src/main/java/org/example/aichat/dto/ChatRequest.java:1)、[`ChatServiceImpl`](src/main/java/org/example/aichat/service/impl/ChatServiceImpl.java:1)、[`RagServiceImpl`](src/main/java/org/example/aichat/service/impl/RagServiceImpl.java:1)、[`AudioController`](src/main/java/org/example/aichat/controller/AudioController.java:1)、[`App.vue`](frontend/src/App.vue:1)

---

## 1. 默认开关汇总

| 能力 | 默认值 | 前端是否暴露 | 由谁决定 |
|---|---|---|---|
| **RAG** | **true**（开） | ❌ 不暴露 | 项目默认。角色卡 / 长期记忆都依赖 RAG，关掉等于失忆 |
| **Tools**（本地 MCP） | **true**（开） | ❌ 不暴露 | 项目默认。Gemma4-31B 原生支持 OpenAI tool-call |
| **Search**（联网搜索） | **false**（关） | ✅ "联网" toggle | 用户按需开启，避免不可控延迟尖峰 |
| 语音通道 Tools | **false**（关） | — | 语音 TTFB 敏感，且兜底部分 Gemma jinja 模板 bug |
| 语音通道 RAG | **true**（开） | — | 否则 AI 答非所问 |
| 语音通道 Search | false | — | 不做联网，保延迟 |

判定逻辑（[`ChatServiceImpl`](src/main/java/org/example/aichat/service/impl/ChatServiceImpl.java:165) 已实现）：
```
useRag    = !Boolean.FALSE.equals(request.getRag())     // null/true → 都开
useTools  = !Boolean.FALSE.equals(request.getTools())   // null/true → 都开
useSearch = Boolean.TRUE.equals(request.getSearch())    // 仅 true 才走
```
即"显式 false 才关"，没传字段时一律按"开"处理。

---

## 2. 故障：「RAG 没生效」全套排查

### 现象
用户提问，AI 答案完全没有触及角色设定 / 知识库内容；后端日志没有 `已注入 RAG 上下文，roleCode=...` 这行。

### 根因优先级（按出现概率）

#### 根因 1：Redis 缓存空 + 启动期没向量化（最常见）
- `application-local.yml` `rag.eager-init: false` 时，启动只读 Redis
- 第一次部署 / `redis-cli FLUSHDB` 后启动 → `chunks` 永远为空
- `retrieveContext` 第一行 `if (chunks.isEmpty()) return ""` 直接返回空，前端 `rag:true` 也白搭

**修复**：已在本 PLAN 落地——`eager-init: true` + 后台异步预热 + 首次同步等 30s。

**验证**：
```bash
# 1. 看启动日志是否有「RAG 异步预热完成，分块数: N」
tail -200 server.log | grep "RAG"

# 2. 看 Redis 里有没有 chunk
redis-cli HLEN rag:chunks:embeds   # 应 > 0

# 3. 兜底手动重建
curl -X POST http://localhost:8080/api/rag/reload
```

#### 根因 2：前端没传 `rag:true`，后端旧版 ChatRequest 默认 false
- 历史代码 `ChatRequest.rag = false`（已在本次提交改为 true）
- 旧前端如果没传字段，`Boolean.TRUE.equals(null)` = false → 跳过 RAG

**修复**：已在本 PLAN 落地——后端 `ChatRequest.rag` 默认 true，且使用 `!Boolean.FALSE.equals` 兼容 null。

**验证**：
```bash
# 不传 rag 字段也要命中
curl -X POST http://localhost:8080/api/chat -H 'Content-Type: application/json' -d '{
  "userId":"1","conversationId":"test","message":"你是谁","roleId":1
}'
# 后端日志应出现「已注入 RAG 上下文」
```

#### 根因 3：Embedding 服务挂了（Win LM Studio 没开 / 模型没加载）
- [`application-local.yml`](src/main/resources/application-local.yml:55) `embedding.base-url` 指向 Win
- Win LM Studio 没启动，或没加载 embedding 模型 → `embeddingModel.embed(query)` 抛异常
- `RagServiceImpl.retrieveContext` catch 后返回 `""`

**验证**：
```bash
# 局域网试一下
curl http://192.168.124.2:1234/v1/models | jq
# 应能看到 text-embedding-qwen3-embedding-0.6b
```
长期方案见 [`00-hardware-and-deployment.md`](.joycode/rules/00-hardware-and-deployment.md:1)：Embedding 必须迁移到 Mac 本机常驻。

#### 根因 4：roleCode 过滤把候选清空
- [`RagServiceImpl.retrieveContext`](src/main/java/org/example/aichat/service/impl/RagServiceImpl.java:140) 用 `roleCode + "_"` 前缀过滤 chunks
- 如果 `role_card.role_code` 为空 / 与文件名不一致 → `candidateChunks` 空
- 比如知识库文件叫 `shu_干员档案.md`，但 `role_card.role_code='Shu'` 大小写不匹配（代码已统一 `toLowerCase`，但角色卡未填则降级全局）

**验证**：
```sql
SELECT id, role_code FROM role_card WHERE id = ?;
```
对照 `src/main/resources/rag/<roleCode>/*.md` 目录名是否完全一致（小写）。

#### 根因 5：相似度阈值过滤
- 代码里 `.filter(sc -> sc.score > 0.1f)` —— 0.1 已经很宽松，正常不会全干掉
- 但如果 query 和知识库语种 / 风格差距太大（中文 query + 英文知识库），可能命中率为 0

---

## 3. 故障：「Tools 调用没触发」

### 现象
LLM 回了一段文本，但没真去查天气 / 算素数 / 查 RAG。

### 排查
1. **看 ChatRequest 是否带 tools=true**（默认 true，旧前端可能传 false）
2. **看是否走的语音通道**：[`AudioController`](src/main/java/org/example/aichat/controller/AudioController.java:151) 强制 `setTools(false)`，这是设计行为
3. **看模型本身**：当前是 Gemma4-31B 原生支持；如果换回老模型（Gemma3 / Qwen 旧版），需要在 LM Studio 选择带 tool-call 的 GGUF 变体
4. **看 LM Studio 日志**：jinja 渲染异常会以 400 错误返回，后端会 fallback 不带 tools 重试

### 验证
```bash
# 让 AI 算一个素数
curl ... -d '{"message":"35是不是素数","roleId":1, ...}'
# 后端日志应该有「触发本地工具调用 isPrime」之类
```

---

## 4. 故障：「联网搜索打不开 / 没用上」

### 现象
打开"联网" toggle 后，AI 回答仍然不带搜索结果。

### 排查
1. 看请求体里 `search:true` 是否真的发出去了（DevTools Network）
2. 看后端日志 `已注入联网搜索结果` 是否出现
3. 看 `executeWebSearch` 实际接的 MCP（默认是智谱 Web Search MCP），需要 `application-local.yml` 里配 API Key 且网络通

---

## 5. 改动后的回归 checklist

- [ ] `mvn -q -DskipTests compile` 通过
- [ ] 启动后看到 `RAG 异步预热完成，分块数: N`
- [ ] 文字提问"你叫什么名字" → 后端日志命中 `已注入 RAG 上下文`
- [ ] 语音提问 → 同样命中 RAG，但日志没有 `触发本地工具`
- [ ] 前端只剩"联网" toggle，无"工具" toggle
- [ ] 联网 toggle 打开 → 请求体 `search:true`、后端日志 `已注入联网搜索结果`

---

## 6. 风险与回退

1. **`eager-init=true` 后启动期 Embedding 接口压力**：异步预热已经避免阻塞，但 Win LM Studio 仍会被几百次 embed 调用打。如果导致 LLM 推理抖动，临时改回 `eager-init: false` + 启动后手动 `POST /api/rag/reload`
2. **首次提问的 30s 同步等待用户体感差**：实际 Mac 本机 embedding 部署到位后单次 embed < 50ms，全量 RAG 预热 < 30s，体感几乎无感；过渡期可在前端加一个"知识库准备中"的 loading 提示
3. **`tools=true` 默认开 + 模型 jinja 模板异常 → 500**：[`ChatServiceImpl`](src/main/java/org/example/aichat/service/impl/ChatServiceImpl.java:1) 已有 fallback 不带 tools 重试逻辑（语音通道更直接强制关）