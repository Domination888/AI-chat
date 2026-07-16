#!/usr/bin/env python3
"""Evaluate RAG min-similarity-score against the Shu story corpus.

The script intentionally mirrors the backend retrieval path:
- chunks are read from Redis hash rag:chunks:embeds
- query embeddings are requested from the configured OpenAI-compatible endpoint
- cosine score is used, with the same memory_cards.jsonl 1.3 weight
"""

from __future__ import annotations

import argparse
import json
import math
import subprocess
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any


@dataclass(frozen=True)
class EvalQuery:
    query: str
    expected_sources: tuple[str, ...]
    note: str


EVAL_QUERIES = [
    EvalQuery(
        "左乐为什么被派到大荒城？",
        ("shu_hs-1_赴大荒_剧情节点.md",),
        "应召回 HS-1 左乐监管黍/下田/多看多想相关节点。",
    ),
    EvalQuery(
        "黍为什么让左乐下田干农活？",
        ("shu_hs-1_赴大荒_剧情节点.md", "shu_story_overview.md"),
        "应召回黍让左乐看见百姓生活、理解粮食来源。",
    ),
    EvalQuery(
        "神农到底是谁，和黍是什么关系？",
        ("shu_story_overview.md", "shu_hs-2_祭神农_剧情节点.md", "shu_hs-8_种因_剧情节点.md"),
        "应召回神农/温柔女性、共同完成农事事业。",
    ),
    EvalQuery(
        "绩为什么要在大荒城作乱？",
        ("shu_story_overview.md", "shu_hs-st-2_织锦缎_剧情节点.md", "shu_hs-5_纺绫罗_剧情节点.md"),
        "应召回绩不愿黍继续承担代价、清算因果和代理人旧账。",
    ),
    EvalQuery(
        "黍为什么会从很多人的记忆里消失？",
        ("shu_story_overview.md", "shu_hs-st-2_织锦缎_剧情节点.md", "shu_profile.md"),
        "应召回黍清除污染/用自身换取大荒城平安的代价。",
    ),
    EvalQuery(
        "万顷水稻代表什么希望？",
        ("shu_hs-8_种因_剧情节点.md", "shu_story_overview.md", "shu_profile.md"),
        "应召回源石污染土地上种粮的希望和长期研究成果。",
    ),
    EvalQuery(
        "望的计划是什么，黍为什么只给他种子？",
        ("shu_story_overview.md", "shu_hs-9_得果_剧情节点.md"),
        "应召回望的终局计划、黍给条件性力量。",
    ),
    EvalQuery(
        "荣晚晴为什么相信黍不是威胁？",
        ("shu_hs-6_卷赤霞_剧情节点.md", "shu_story_overview.md"),
        "应召回荣晚晴离开司岁台、长期守护大荒城。",
    ),
    EvalQuery(
        "十二楼五城和巨兽心脏是为了什么？",
        ("shu_hs-st-1_禾下梦_剧情节点.md", "shu_story_overview.md"),
        "应召回为岁兽代理人创造未来依凭。",
    ),
    EvalQuery(
        "小满为什么执意去找沉默樵夫？",
        ("shu_hs-6_卷赤霞_剧情节点.md", "shu_hs-2_祭神农_剧情节点.md"),
        "应召回小满相信大荒城每个人都要平安、樵夫和旧牺牲有关。",
    ),
]


def post_json(url: str, payload: dict[str, Any], timeout: int = 120) -> dict[str, Any]:
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"HTTP {exc.code} from {url}: {body}") from exc


def get_json(url: str, timeout: int = 10) -> dict[str, Any] | None:
    try:
        with urllib.request.urlopen(url, timeout=timeout) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except Exception:
        return None


def redis_hgetall(key: str) -> list[dict[str, Any]]:
    proc = subprocess.run(
        ["redis-cli", "--raw", "HGETALL", key],
        check=True,
        text=True,
        capture_output=True,
    )
    lines = proc.stdout.splitlines()
    chunks: list[dict[str, Any]] = []
    for i in range(1, len(lines), 2):
        try:
            chunks.append(json.loads(lines[i]))
        except json.JSONDecodeError as exc:
            raise RuntimeError(f"Failed to parse Redis value at pair {i // 2}: {exc}") from exc
    return chunks


def embed(base_url: str, model: str, texts: list[str]) -> list[list[float]]:
    payload = {"model": model, "input": texts}
    body = post_json(f"{base_url.rstrip('/')}/embeddings", payload)
    if "error" in body:
        raise RuntimeError(str(body["error"]))
    data = sorted(body["data"], key=lambda item: item["index"])
    return [item["embedding"] for item in data]


def cosine(a: list[float], b: list[float]) -> float:
    if not a or not b or len(a) != len(b):
        return 0.0
    dot = sum(x * y for x, y in zip(a, b))
    norm_a = math.sqrt(sum(x * x for x in a))
    norm_b = math.sqrt(sum(y * y for y in b))
    return dot / (norm_a * norm_b) if norm_a > 1e-9 and norm_b > 1e-9 else 0.0


def weighted_score(source: str, raw: float) -> float:
    return raw * 1.3 if source.lower().endswith("memory_cards.jsonl") else raw


def is_expected(chunk: dict[str, Any], expected_sources: tuple[str, ...]) -> bool:
    source = str(chunk.get("source", ""))
    return any(source.startswith(expected) for expected in expected_sources)


def evaluate(args: argparse.Namespace) -> str:
    models = get_json(f"{args.base_url.rstrip('/').removesuffix('/v1')}/api/v0/models")
    model_type = "unknown"
    model_state = "unknown"
    if models:
        for item in models.get("data", []):
            if item.get("id") == args.model:
                model_type = item.get("type", "unknown")
                model_state = item.get("state", "unknown")
                break

    chunks = [
        c for c in redis_hgetall(args.redis_key)
        if str(c.get("source", "")).startswith(args.role_prefix)
    ]
    if not chunks:
        raise RuntimeError(f"No chunks found for prefix {args.role_prefix!r} in {args.redis_key}")

    chunk_embedding_source = "Redis cached embeddings"
    if args.reembed_chunks:
        texts = [str(c.get("text", "")) for c in chunks]
        chunk_vectors: list[list[float]] = []
        batch_size = max(1, args.chunk_embedding_batch_size)
        for start in range(0, len(texts), batch_size):
            end = min(start + batch_size, len(texts))
            chunk_vectors.extend(embed(args.base_url.rstrip("/"), args.model, texts[start:end]))
        for chunk, vector in zip(chunks, chunk_vectors):
            chunk["embedding"] = vector
        chunk_embedding_source = (
            f"Re-embedded for this eval only, batch_size={batch_size}; Redis was not modified"
        )

    query_vectors = embed(args.base_url.rstrip("/"), args.model, [q.query for q in EVAL_QUERIES])

    rows: list[dict[str, Any]] = []
    for q, qvec in zip(EVAL_QUERIES, query_vectors):
        scored = []
        for chunk in chunks:
            raw = cosine(qvec, chunk.get("embedding") or [])
            score = weighted_score(str(chunk.get("source", "")), raw)
            scored.append((score, raw, chunk))
        scored.sort(key=lambda item: item[0], reverse=True)
        top = scored[: args.top_k]
        rows.append(
            {
                "query": q,
                "top": top,
                "best_score": scored[0][0] if scored else 0.0,
                "best_raw": scored[0][1] if scored else 0.0,
                "best_source": scored[0][2].get("source", "") if scored else "",
            }
        )

    thresholds = [float(x) for x in args.thresholds.split(",")]
    lines: list[str] = []
    lines.append("# RAG min-similarity-score 实测报告")
    lines.append("")
    lines.append(f"- 时间：{time.strftime('%Y-%m-%d %H:%M:%S')}")
    lines.append(f"- Embedding API：`{args.base_url}`")
    lines.append(f"- 模型：`{args.model}`（LM Studio type=`{model_type}`, state=`{model_state}`）")
    lines.append(f"- Redis key：`{args.redis_key}`")
    lines.append(f"- 评测分块：{len(chunks)}")
    lines.append(f"- 分块向量来源：{chunk_embedding_source}")
    lines.append(f"- 查询集：{len(EVAL_QUERIES)} 个黍剧情问题")
    lines.append("")
    lines.append("## 阈值扫描")
    lines.append("")
    lines.append("| threshold | 有召回 | 期望命中 | 空召回 | 最高分低于阈值 |")
    lines.append("|---:|---:|---:|---:|---:|")
    best_threshold = None
    best_tuple = (-1, -1, -1.0)
    for threshold in thresholds:
        recalled = 0
        expected_hits = 0
        empty = 0
        below_best = 0
        for row in rows:
            kept = [item for item in row["top"] if item[0] > threshold]
            if kept:
                recalled += 1
            else:
                empty += 1
            if row["best_score"] <= threshold:
                below_best += 1
            if any(is_expected(item[2], row["query"].expected_sources) for item in kept):
                expected_hits += 1
        lines.append(f"| {threshold:.2f} | {recalled} | {expected_hits} | {empty} | {below_best} |")
        candidate = (expected_hits, recalled, threshold)
        if candidate > best_tuple:
            best_tuple = candidate
            best_threshold = threshold

    lines.append("")
    lines.append(f"建议阈值：`{best_threshold:.2f}`（在本查询集上期望命中最多；同分时选择更高阈值以减少噪声）。")
    lines.append("")
    lines.append("## 单问题 Top 结果")
    lines.append("")
    for row in rows:
        q: EvalQuery = row["query"]
        lines.append(f"### {q.query}")
        lines.append("")
        lines.append(f"- 期望：{q.note}")
        lines.append(f"- best：`{row['best_source']}` score={row['best_score']:.4f} raw={row['best_raw']:.4f}")
        for rank, (score, raw, chunk) in enumerate(row["top"], 1):
            text = " ".join(str(chunk.get("text", "")).split())
            if len(text) > 140:
                text = text[:140] + "..."
            mark = "OK" if is_expected(chunk, q.expected_sources) else "NO"
            lines.append(
                f"{rank}. `{chunk.get('source')}#{chunk.get('chunkIndex')}` "
                f"score={score:.4f} raw={raw:.4f} {mark} - {text}"
            )
        lines.append("")

    return "\n".join(lines).rstrip() + "\n"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:1234/v1")
    parser.add_argument("--model", default="text-embedding-qwen3-embedding-4b")
    parser.add_argument("--redis-key", default="rag:chunks:embeds")
    parser.add_argument("--role-prefix", default="shu_")
    parser.add_argument("--top-k", type=int, default=5)
    parser.add_argument("--reembed-chunks", action="store_true")
    parser.add_argument("--chunk-embedding-batch-size", type=int, default=8)
    parser.add_argument("--thresholds", default="0.35,0.40,0.45,0.50,0.55,0.60,0.65,0.70,0.75,0.80")
    parser.add_argument("--output", default="doc/rag-threshold-eval.md")
    args = parser.parse_args()

    try:
        report = evaluate(args)
    except Exception as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1

    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(report, encoding="utf-8")
    print(f"Wrote {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
