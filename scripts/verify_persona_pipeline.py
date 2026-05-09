#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
verify_persona_pipeline.py — 离线冒烟验证 PLAN-005 数据管线

不启动后端、不连 LLM，纯本地模拟两条链路：
  1) persona_card.json 渲染到 role_system.txt → 打印最终 system prompt
  2) memory_cards.jsonl 按 RagServiceImpl.parseMemoryCardLine 相同规则生成
     检索文本，打印前 5 条，确认能被 Embedding 看到

目的：证明两个卡片真的接入了整套流程（格式对齐、字段对齐、路径对齐）。
"""
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PERSONA = ROOT / "data/processed/shu/persona_card.json"
MEMORY = ROOT / "src/main/resources/rag/shu/memory_cards.jsonl"
TEMPLATE = ROOT / "src/main/resources/prompts/role_system.txt"


def render_personality_block(p: dict) -> str:
    """复刻 RoleCardService.renderPersonalityBlock 的 Java 逻辑。"""
    out = []

    def bullets(key, title):
        arr = p.get(key) or []
        if not arr:
            return
        out.append(f"{title}：")
        for item in arr:
            if isinstance(item, str) and item.strip():
                out.append(f"- {item.strip()}")

    bullets("personality", "核心性格")
    if p.get("speech_style"):
        out.append(f"说话风格：{p['speech_style'].strip()}")
    bullets("catchphrases", "口癖")
    bullets("taboo", "禁忌")
    bullets("output_rules", "输出规则")

    rel = p.get("relationships") or []
    if rel:
        out.append("关系：")
        for r in rel:
            who = (r.get("who") or "").strip()
            how = (r.get("how") or "").strip()
            if who and how:
                out.append(f"- {who}:{how}")
    return "\n".join(out).strip()


def render_example_dialogue(p: dict) -> str:
    arr = p.get("example_dialogue") or []
    if not arr:
        return ""
    name = p.get("name", "AI")
    out = []
    for t in arr:
        u = (t.get("user") or "").strip()
        a = (t.get("assistant") or "").strip()
        if u:
            out.append(f"User: {u}")
        if a:
            out.append(f"{name}: {a}")
        out.append("")
    return "\n".join(out).strip()


def parse_memory_card_line(line: str) -> str:
    """复刻 RagServiceImpl.parseMemoryCardLine 的 Java 逻辑。"""
    d = json.loads(line)
    t = d.get("type", "").strip()
    c = d.get("content", "").strip()
    if not c:
        return ""
    s = f"[{t}] {c}" if t else c
    kws = d.get("keywords") or []
    if kws:
        s += " 关键词:" + " ".join(kws)
    return s


def main():
    persona = json.loads(PERSONA.read_text("utf-8"))
    template = TEMPLATE.read_text("utf-8")

    vars_map = {
        "name": persona.get("name", ""),
        "profile": persona.get("identity", ""),
        "background": persona.get("background_oneliner", ""),
        "personality": render_personality_block(persona),
        "exampleDialogue": render_example_dialogue(persona),
    }

    rendered = template
    for k, v in vars_map.items():
        rendered = rendered.replace("{{" + k + "}}", v)

    print("=" * 70)
    print("✅ 链路1: persona_card.json → system prompt 渲染结果")
    print("=" * 70)
    print(rendered)
    print(f"\n[System prompt 长度] {len(rendered)} 字符 (≈ {len(rendered)//3} tokens)\n")

    print("=" * 70)
    print("✅ 链路2: memory_cards.jsonl → RAG chunk 文本（前 5 条）")
    print("=" * 70)
    lines = [ln for ln in MEMORY.read_text("utf-8").splitlines() if ln.strip()]
    for i, ln in enumerate(lines[:5], 1):
        print(f"[chunk {i}] {parse_memory_card_line(ln)}")
    print(f"\n[Memory chunks 总数] {len(lines)} 条 (检索时分数 ×1.3)")


if __name__ == "__main__":
    main()