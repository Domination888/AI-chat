# RAG 内容与切分策略报告

更新时间：2026-07-01

## 当前结论

RAG 切分方式已经从固定字符滑窗重构为按材料类型分流的语义切分。

旧实现会按 `500` 字符、`80` 重叠直接 `substring`，导致半句截断、剧情节点混切、语音台词混切。新实现不再按固定长度切普通材料，而是优先使用作者已经写好的结构边界：

- 剧情节点文件：按 `## 节点 N：...` 一节点一块。
- 语音记录：按 `### 语音场景` 一条语音一块。
- 普通 Markdown：按标题、独占一行的 `【章节】`、段落聚合切分。
- 纯文本：按段落聚合。
- 超长块：只作为兜底按句子边界拆分，避免重新出现半句截断。
- 记忆卡：继续保持 `memory_cards.jsonl` 一行一块。

当前本机 Redis 已重建为新索引，新切分器对现有黍语料的结果是 `212` 个 chunk。

2026-07-02 追加：复查 prompt 日志时发现“RAG 全没了”的直接原因不是新切分器丢资料，而是 `rag.min-similarity-score=0.8` 对当前 embedding 模型过高。问题“当时左乐为什么被派到大荒城？”的最佳候选分数约为 `0.5830`，被 0.8 阈值过滤。后续用 10 个黍剧情问题扫描 `text-embedding-embeddinggemma-300m`，`0.45` 是当前更合适的默认阈值：10 个问题均有召回，9 个命中预期；从 `0.50` 开始命中下降，`0.55` 已出现 3 个空召回。

2026-07-02 追加：切换到 `qwen3-embedding-4b-dwq` 后，发现当前 LM Studio 将它暴露为 `type=llm` 而不是 `type=embeddings`，`/v1/embeddings` 返回 `No models loaded`。因此 qwen3 这次还不能产出可信阈值；必须先换成 LM Studio 识别为 embeddings 的模型，或使用能提供 qwen3 embedding 向量的服务，再运行 `scripts/evaluate_rag_threshold.py` 复测。

2026-07-02 追加：新加载的 `text-embedding-qwen3-embedding-4b` 已被 LM Studio 正确暴露为 `type=embeddings`（GGUF Q8_0，输出 2560 维）。使用该模型重建 Redis 后，`rag:chunks:embeds` 中 212 个分块均为 2560 维。按线上实际 `topK=3` 扫描 10 个黍剧情问题，`rag.min-similarity-score=0.60` 表现最好：10/10 有召回，10/10 命中预期；`0.65` 开始期望命中下降，`0.70` 开始出现空召回。评测报告见 `doc/rag-threshold-eval-qwen3-embedding-4b-top3.md`。

## 存储结构

角色知识库仍存储在 Redis：

- Redis key：`rag:chunks:embeds`
- 类型：Hash
- 单条 value：`RagChunk`
- 字段：`source`、`chunkIndex`、`text`、`terms`、`embedding`

单条文本现在会带上稳定来源路径：

```text
来源：HS-1 赴大荒 · 剧情节点摘要 / 核心关键词：黍、左乐、小满、禾生、牧兽、司岁台、大荒城、十二楼五城、农务、看见人间。 / ## 节点 3：黍让左乐做农活
内容：
小满指出大荒城的人都要与庄稼打交道，黍顺势让左乐学习锄草、照料农具和跟随禾生...
```

这样 embedding 和最终注入上下文都能看到“这段是什么材料、属于哪个节点/场景”。

## 新切分结果

现有 `backend/src/main/resources/personas/shu/lore` 静态统计：

| source | 新 chunk 数 | 最短长度 | 最长长度 |
| --- | ---: | ---: | ---: |
| `shu_items.md` | 1 | 104 | 104 |
| `shu_module.md` | 1 | 325 | 325 |
| `shu_profile.md` | 7 | 68 | 1010 |
| `shu_story_overview.md` | 9 | 133 | 606 |
| `shu_voice.md` | 37 | 24 | 161 |
| `shu_hs-1_赴大荒_剧情节点.md` | 7 | 179 | 209 |
| `shu_hs-2_祭神农_剧情节点.md` | 8 | 166 | 215 |
| `shu_hs-3_早芒种_剧情节点.md` | 8 | 172 | 214 |
| `shu_hs-4_话桑麻_剧情节点.md` | 8 | 178 | 214 |
| `shu_hs-5_纺绫罗_剧情节点.md` | 8 | 181 | 207 |
| `shu_hs-6_卷赤霞_剧情节点.md` | 8 | 176 | 202 |
| `shu_hs-7_梦四时_剧情节点.md` | 6 | 172 | 190 |
| `shu_hs-8_种因_剧情节点.md` | 9 | 178 | 207 |
| `shu_hs-9_得果_剧情节点.md` | 12 | 169 | 205 |
| `shu_hs-st-1_禾下梦_剧情节点.md` | 6 | 198 | 241 |
| `shu_hs-st-2_织锦缎_剧情节点.md` | 5 | 181 | 190 |
| `shu_hs-st-3_彻风雨_剧情节点.md` | 8 | 158 | 204 |

普通文本小计：`148` 个 chunk。

`memory_cards.jsonl`：`64` 个 chunk。

新索引总计：`212` 个 chunk。

## 和旧切分相比

旧索引：

- 普通文本：57 个 chunk。
- 记忆卡：64 个 chunk。
- 总计：121 个 chunk。

新索引：

- 普通文本：148 个 chunk。
- 记忆卡：64 个 chunk。
- 总计：212 个 chunk。

增加的 chunk 主要来自两个地方：

- 剧情摘要从滑窗改为一节点一块，剧情问题会命中更精确的节点。
- 语音记录从多条混切改为一条语音一块，口癖、台词、场景召回更准。

这不是按长度切得更碎，而是把原来混在一起的语义单元拆回独立单元。

## 无用信息检查

当前进入 RAG 的来源仍然只包括：

- `personas/*/lore/**/*.md`
- `personas/*/lore/**/*.txt`
- `personas/*/memory_cards.jsonl`
- 历史兼容目录 `rag/*.md`、`rag/*.txt`

没有把 `node_modules`、`venv`、前端代码、HTML 模板或原始抓取 JSON 放入 RAG。

`persona_card.json` 和 `greetings.txt` 仍不进入 `rag:chunks:embeds`。这是刻意保留：角色卡适合走系统提示/角色加载，开场白不适合做检索资料。

## 已改代码

核心改动：

- 新增 `RagTextSplitter`：集中处理不同材料的语义切分。
- `RagServiceImpl.reload()`：改为调用 `RagTextSplitter.split()`，不再使用固定滑窗 substring。
- `RagServiceImpl.reload()`：启用 `rag.embedding-batch-size`，按批调用 embedding，避免 chunk 增多后一次性请求过大。
- `RagServiceImpl.reload()`：改为串行执行，避免启动重建和热更新重建同时写 Redis，导致新旧索引互相覆盖。
- `RuntimeConfigService`：embedding 配置实际变化后会刷新 embedding client，并异步触发 RAG 重建，避免“查询模型已换、Redis 索引仍是旧模型”的错配。
- 新增 `RagTextSplitterTest`：覆盖剧情节点、语音记录、档案章节三类材料。
- 新增 `scripts/evaluate_rag_threshold.py`：用黍剧情问题批量扫描 `min-similarity-score`，报告输出到 `doc/rag-threshold-eval-*.md`。

## 重新构建索引

加载新代码后执行：

```bash
curl http://127.0.0.1:8080/api/rag/reload
```

预期返回：

```json
{"success":true,"chunkCount":212}
```

如果返回 `121`，说明后端进程仍是旧代码，需要先重启后端。

## 检索阈值

推荐参数总表见 `doc/rag-embedding-recommended-params.md`。

当前默认：

```yaml
rag:
  min-similarity-score: 0.60
```

这个值是按 `text-embedding-qwen3-embedding-4b` + 当前 212 个黍 RAG 分块实测得到的结果。评测报告见 `doc/rag-threshold-eval-qwen3-embedding-4b-top3.md`。

不同 embedding 模型的 cosine 分布差异很大，换模型后必须重建 RAG 索引并重新跑阈值评测。此前的 `qwen3-embedding-4b-dwq` MLX 模型在 LM Studio 中是 `type=llm`，不是 `type=embeddings`，不能直接用于后端 OpenAI embeddings API；当前可用的是 GGUF 模型 `text-embedding-qwen3-embedding-4b`。

当未命中时，后端会输出类似：

```text
RAG 未命中：最高分未过阈值 query='...', roleCode=shu, candidates=212, threshold=0.55, bestSource=..., bestRawScore=..., bestWeightedScore=...
```

这条日志用于判断是资料不存在、roleCode 过滤错了，还是阈值过高。
