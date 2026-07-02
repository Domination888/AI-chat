# RAG Embedding 推荐参数

更新时间：2026-07-02

## 当前推荐

| Embedding 模型 | LM Studio 类型 | 向量维度 | 推荐 `rag.min-similarity-score` | 评测 topK | 评测结果 | 适用状态 |
| --- | --- | ---: | ---: | ---: | --- | --- |
| `text-embedding-qwen3-embedding-4b` | `embeddings` | 2560 | `0.60` | 3 | 10/10 有召回，10/10 命中预期 | 当前推荐 |
| `text-embedding-embeddinggemma-300m` | `embeddings` | 768 | `0.45` | 5 | 10/10 有召回，9/10 命中预期 | 轻量备用 |

当前默认配置应使用：

```yaml
embedding:
  base-url: http://127.0.0.1:1234/v1
  model-name: text-embedding-qwen3-embedding-4b

rag:
  min-similarity-score: 0.60
```

## 评测依据

- `text-embedding-qwen3-embedding-4b`：报告见 `doc/rag-threshold-eval-qwen3-embedding-4b-top3.md`。该模型已在 LM Studio 中正确暴露为 `type=embeddings`，Redis `rag:chunks:embeds` 已重建为 212 个 2560 维分块。
- `text-embedding-embeddinggemma-300m`：报告见 `doc/rag-threshold-eval-embeddinggemma.md`。该模型分数分布整体低于 Qwen3，阈值升到 `0.55` 后会出现明显空召回。

## 注意事项

不同 embedding 模型的 cosine 分布不能共用阈值。切换模型后必须先重建 RAG 索引，再重新评测阈值：

```bash
curl http://127.0.0.1:8080/api/rag/reload

python3 scripts/evaluate_rag_threshold.py \
  --model <embedding-model-name> \
  --top-k 3 \
  --output doc/rag-threshold-eval-<model>.md
```

此前测试过的 `qwen3-embedding-4b-dwq` 是 MLX safetensors 模型，在 LM Studio 中暴露为 `type=llm`，不是 `type=embeddings`，不能作为后端 OpenAI embeddings API 的可靠来源。
