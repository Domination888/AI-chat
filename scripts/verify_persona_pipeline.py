#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Persona 装配预览脚本（对齐 ChatServiceImpl.chatStream 的真实拼接）
=================================================================

输出 docs/prompt_preview_shu.md，按 LangChain4j 实际传给 LLM 的消息列表
顺序展示：
    [SystemMessage]            ← prompts/system.txt + prompts/role_system.txt 渲染 + 长期记忆/事实
    [历史 UserMessage/AiMessage] (滑窗)
    [UserMessage: RAG 检索结果] + [AiMessage: "好的，我已了解..."]   ← 插入到当前问题之前
    [UserMessage: 当前问题]

RAG 检索是用关键字粗匹配模拟的（真实是 bge embedding 余弦），仅用于
直观展示注入内容的形态与长度。
"""

from __future__ import annotations
import json
from pathlib import Path

WORKSPACE       = Path(__file__).resolve().parent.parent
SYSTEM_TXT      = WORKSPACE / "backend/src/main/resources/prompts/system.txt"
ROLE_TEMPLATE   = WORKSPACE / "backend/src/main/resources/prompts/role_system.txt"
PERSONA_JSON    = WORKSPACE / "backend/src/main/resources/personas/shu/persona_card.json"
MEMORY_CARDS    = WORKSPACE / "backend/src/main/resources/personas/shu/memory_cards.jsonl"
STORIES_DIR     = WORKSPACE / "backend/src/main/resources/personas/shu/lore/stories"
LORE_DIR        = WORKSPACE / "backend/src/main/resources/personas/shu/lore"
OUTPUT_DOC      = WORKSPACE / "docs" / "prompt_preview_shu.md"


# =================== 复刻 RoleCardService 字段渲染 ===================

def render_aka(persona: dict) -> str:
    arr = persona.get("aka") or []
    return " / ".join(a for a in arr if a)


def append_line(out: list[str], title: str, text: str | None) -> None:
    if text and text.strip():
        out.append(f"{title}：{text.strip()}")


def append_bullets(out: list[str], title: str, items: list[str] | None) -> None:
    items = [i.strip() for i in (items or []) if i and i.strip()]
    if not items:
        return
    out.append(f"{title}：")
    out.extend(f"- {i}" for i in items)


def render_persona(persona: dict) -> str:
    card = persona.get("persona")
    if isinstance(card, str):
        return card.strip()
    if isinstance(card, dict):
        out = []
        append_line(out, "身份", card.get("identity"))
        append_line(out, "来历", card.get("origin"))
        append_line(out, "外貌", card.get("appearance"))
        append_bullets(out, "性格与日常", card.get("traits"))
        append_line(out, "说话方式", card.get("speech"))
        append_bullets(out, "扮演规则", card.get("rules"))
        return "\n".join(out).strip()

    return ""


def render_relationships(persona: dict) -> str:
    out = []
    for item in persona.get("relationships") or []:
        who, how = item.get("who", "").strip(), item.get("how", "").strip()
        if who and how:
            out.append(f"- {who}：{how}")
    return "\n".join(out).strip()


def render_examples(persona: dict) -> str:
    arr = persona.get("examples") or []
    role_name = persona.get("name") or "AI"
    out = []
    for turn in arr:
        u = (turn.get("user") or "").strip()
        a = (turn.get("assistant") or "").strip()
        if not (u or a):
            continue
        if u: out.append(f"User: {u}")
        if a: out.append(f"{role_name}: {a}")
        out.append("")
    return "\n".join(out).strip()


def render_template(template: str, vars: dict[str, str]) -> str:
    out = template
    for k, v in vars.items():
        out = out.replace("{{" + k + "}}", v)
    return out


def build_role_system_layer() -> tuple[str, dict]:
    persona = json.loads(PERSONA_JSON.read_text(encoding="utf-8"))
    template = ROLE_TEMPLATE.read_text(encoding="utf-8")
    vars = {
        "name":          persona.get("name", ""),
        "aka":           render_aka(persona),
        "persona":       render_persona(persona),
        "relationships": render_relationships(persona),
        "examples":      render_examples(persona),
        "mantra":        persona.get("mantra", ""),
    }
    return render_template(template, vars), vars


def build_full_system_message() -> str:
    """对齐 RoleCardService.buildSystemPrompt：base + '\\n\\n' + role_layer。"""
    base = SYSTEM_TXT.read_text(encoding="utf-8").strip()
    role_layer, _ = build_role_system_layer()
    return base + "\n\n" + role_layer.strip()


# =================== RAG 检索效果模拟 ===================

def gather_lore_chunks() -> list[tuple[str, str]]:
    """收集 lore/*.md + lore/stories/*.md + memory_cards.jsonl，对齐 RagService.reload 的扫描范围。"""
    candidates = []
    for p in sorted(LORE_DIR.glob("*.md")):
        candidates.append((f"shu_{p.name}", p.read_text(encoding="utf-8")))
    if STORIES_DIR.exists():
        for p in sorted(STORIES_DIR.glob("*.md")):
            candidates.append((f"shu_{p.name}", p.read_text(encoding="utf-8")))
    if MEMORY_CARDS.exists():
        for i, line in enumerate(MEMORY_CARDS.read_text(encoding="utf-8").splitlines()):
            if not line.strip():
                continue
            try:
                obj = json.loads(line)
                txt = f"[{obj.get('type','')}] {obj.get('content','')}  关键词:{' '.join(obj.get('keywords') or [])}"
            except Exception:
                txt = line
            candidates.append((f"shu_memory_cards.jsonl#{i}", txt))
    return candidates


def fake_retrieve(query: str, top_k: int = 3) -> list[tuple[str, float, str]]:
    """关键字粗排模拟（仅审阅用，线上是 bge embedding 余弦相似度）。"""
    chunks = gather_lore_chunks()
    qchars = set(c for c in query if "\u4e00" <= c <= "\u9fff" or c.isalnum())
    scored = []
    for src, txt in chunks:
        hit = sum(1 for c in qchars if c in txt)
        if hit == 0:
            continue
        weight = 1.3 if "memory_cards" in src else 1.0  # 对齐 RagService 的 memory_cards 加权
        score = hit / max(len(txt), 1) * weight * 100
        scored.append((src, score, txt.strip()))
    scored.sort(key=lambda x: x[1], reverse=True)
    return scored[:top_k]


def render_rag_context(query: str, top_k: int = 3, max_chars: int = 2400) -> str:
    """对齐 RagService.retrieveContext 的输出格式。"""
    hits = fake_retrieve(query, top_k)
    if not hits:
        return ""
    out = ["【本地知识库检索结果】"]
    used = 0
    for i, (src, score, txt) in enumerate(hits, 1):
        snippet = txt[:600]
        item = f"[片段{i}|来源:{src}|相似度:{score:.2f}]\n{snippet}\n"
        if used + len(item) > max_chars:
            break
        out.append(item)
        used += len(item)
    return "\n".join(out)


# =================== 主流程 ===================

def estimate_tokens(s: str) -> int:
    """中文按 1 字 ≈ 1 token、ascii 按 4 字 ≈ 1 token 的粗略估算。"""
    cn = sum(1 for c in s if "\u4e00" <= c <= "\u9fff")
    other = len(s) - cn
    return cn + other // 4


def main() -> int:
    role_system_md, vars = build_role_system_layer()
    system_base = SYSTEM_TXT.read_text(encoding="utf-8").strip()
    full_system_msg = build_full_system_message()

    # 三个示例 query，覆盖典型对话场景
    sample_queries = [
        "你真的能预知未来吗？",
        "我最近压力很大，有点撑不住。",
        "你和年是亲姐妹吗？你们关系怎么样？",
    ]

    md = []
    md.append("# 黍 · Prompt 装配预览（审阅文档）")
    md.append("")
    md.append("> 自动生成自 `scripts/verify_persona_pipeline.py`，对齐后端 "
              "`ChatServiceImpl.chatStream` 的真实消息装配顺序。")
    md.append("")
    md.append("**LangChain4j 实际发给 LLM 的消息列表顺序**：")
    md.append("")
    md.append("```")
    md.append("[1] SystemMessage:        prompts/system.txt   +  prompts/role_system.txt（由 persona_card.json 渲染）")
    md.append("                          +  Memos 长期记忆 / 用户事实记忆 / 用户偏好（如果命中）")
    md.append("[2] (历史 ChatMemory 消息，滑窗最近 20 条)")
    md.append("[3] UserMessage:          【联网搜索结果】（如果开了搜索且命中）")
    md.append("[4] AiMessage:            「好的，我已了解搜索结果...」    占位回应")
    md.append("[5] UserMessage:          【本地知识库检索结果】 ← RAG 在这里注入，不在 system 里！")
    md.append("[6] AiMessage:            「好的，我已了解这些信息...」  占位回应")
    md.append("[7] UserMessage:          (用户本轮真正的问题)")
    md.append("```")
    md.append("")

    md.append("---")
    md.append("")
    md.append("## 第 1 段 · 完整 SystemMessage")
    md.append("")
    md.append("由 `RoleCardService.buildSystemPrompt` 返回，包含「破甲基底」+「角色层」两块（中间空一行）。")
    md.append("")
    md.append("### 1.1 破甲基底 (`prompts/system.txt`)")
    md.append("")
    md.append("```")
    md.append(system_base)
    md.append("```")
    md.append("")
    md.append(f"长度：{len(system_base)} 字 / 估算 {estimate_tokens(system_base)} token")
    md.append("")
    md.append("### 1.2 角色层 (`prompts/role_system.txt` 渲染后)")
    md.append("")
    md.append("```")
    md.append(role_system_md.strip())
    md.append("```")
    md.append("")
    md.append(f"长度：{len(role_system_md)} 字 / 估算 {estimate_tokens(role_system_md)} token")
    md.append("")
    md.append("### 1.3 渲染所用变量明细（按 `RoleCardService.buildVarsFromRole` 顺序）")
    md.append("")
    for k, v in vars.items():
        preview = v.replace("\n", " ⏎ ")
        if len(preview) > 220:
            preview = preview[:220] + "..."
        md.append(f"- `{{{{{k}}}}}` → {preview}")
    md.append("")
    md.append(f"### 1.4 SystemMessage 合计")
    md.append("")
    md.append(f"- 总长度：**{len(full_system_msg)} 字 / 约 {estimate_tokens(full_system_msg)} token**")
    md.append(f"- LM Studio ctx_len = 8192 token，**system 占 {estimate_tokens(full_system_msg) * 100 // 8192}%**")
    md.append("")

    md.append("---")
    md.append("")
    md.append("## 第 2 段 · RAG 注入示例（不进 system，作为伪历史轮插入）")
    md.append("")
    md.append("RAG 命中结果不会拼到 SystemMessage 里，而是以 "
              "`UserMessage(\"【本地知识库检索结果】...\") + AiMessage(\"好的，我已了解...\")` "
              "的形式插入到「当前真实问题」之前，让 LLM 把检索结果当作上一轮已确认的上下文。")
    md.append("")
    md.append("> 下面的检索结果是用关键字粗匹配模拟的，**线上是 bge-small embedding 余弦相似度**，命中和顺序会更准。")
    md.append("> 这里只用来给你直观感受会被注入什么样的语料片段。")
    md.append("")
    for q in sample_queries:
        md.append(f"### 用户问：「{q}」")
        md.append("")
        rag = render_rag_context(q, top_k=3)
        if rag:
            md.append("```")
            md.append(rag)
            md.append("```")
            md.append("")
            md.append(f"长度：{len(rag)} 字 / 估算 {estimate_tokens(rag)} token")
        else:
            md.append("（无命中）")
        md.append("")

    md.append("---")
    md.append("")
    md.append("## 第 3 段 · 完整消息列表（以第 2 个示例问题演示）")
    md.append("")
    sample_q = sample_queries[1]
    rag_ctx = render_rag_context(sample_q, top_k=3)
    md.append(f"假设当前 ChatMemory 为空（首轮对话），用户问：「{sample_q}」")
    md.append("")
    md.append("```")
    md.append("=========== [1] SystemMessage ===========")
    md.append(full_system_msg)
    md.append("")
    md.append("=========== [5] UserMessage (RAG 注入) ===========")
    md.append(rag_ctx if rag_ctx else "（无命中，本步跳过）")
    md.append("")
    md.append('=========== [6] AiMessage (占位) ===========')
    md.append("好的，我已了解这些信息，会结合上下文回答。")
    md.append("")
    md.append("=========== [7] UserMessage (本轮问题) ===========")
    md.append(sample_q)
    md.append("```")
    md.append("")

    total_chars = len(full_system_msg) + (len(rag_ctx) if rag_ctx else 0) + len(sample_q) + 40
    md.append(f"**单轮 prompt 总长**：约 {total_chars} 字 / **{estimate_tokens(full_system_msg) + estimate_tokens(rag_ctx or '') + estimate_tokens(sample_q)} token**")
    md.append("")
    md.append(f"**预算检查**：LM Studio ctx_len=8192 token；当前占用约 "
              f"{(estimate_tokens(full_system_msg) + estimate_tokens(rag_ctx or '')) * 100 // 8192}%，"
              f"剩余 >5000 token 给历史滑窗 + 模型输出，安全。")
    md.append("")

    md.append("---")
    md.append("")
    md.append("## 第 4 段 · 阶段 2 切片建库统计")
    md.append("")
    if STORIES_DIR.exists():
        files = list(STORIES_DIR.glob("*.md"))
        total = sum(f.stat().st_size for f in files)
        per_stage = {}
        for f in files:
            stage = f.name.split("__")[0]
            per_stage.setdefault(stage, [0, 0])
            per_stage[stage][0] += 1
            per_stage[stage][1] += f.stat().st_size
        md.append(f"- 切片产物：**{len(files)} 个场景文件**，位于 `backend/src/main/resources/personas/shu/lore/stories/`")
        md.append(f"- 总字节：{total} ({total/1024:.1f} KB)")
        md.append(f"- 启动时由 `RagService.reload()` 自动扫描进入索引，与原有 `lore/*.md`、`memory_cards.jsonl` 一起按 `source` 前缀 `shu_` 隔离")
        md.append("")
        md.append("各关卡分布：")
        md.append("")
        md.append("| 关卡 | 场景数 | 字节 |")
        md.append("|------|-------:|-----:|")
        for stage in sorted(per_stage.keys()):
            n, sz = per_stage[stage]
            md.append(f"| {stage} | {n} | {sz} |")
    else:
        md.append("（stories 目录尚未生成，请先运行 `python3 scripts/slice_shu_stories.py`）")
    md.append("")

    md.append("---")
    md.append("")
    md.append("---")
    md.append("")
    md.append("## 第 5 段 · persona_card 结构收敛")
    md.append("")
    md.append("| 主体 | 说明 |")
    md.append("|------|------|")
    md.append("| `persona` | 合并身份、来历、外貌、性格、说话方式、扮演规则；角色卡不再拆散成多组相互覆盖的字段 |")
    md.append("| `relationships` | 只放人物关系，不再混进人格规则 |")
    md.append("| `examples` | 对话样例，字段名和模板占位符一致 |")
    md.append("| `mantra` | 角色层最后一句铭印 |")
    md.append("| 兼容策略 | 没有 persona_card 时仍回退数据库字段；有 persona_card 时只认这四个主体块 |")
    md.append("")
    md.append("")

    md.append("---")
    md.append("")
    md.append("## 第 6 段 · 接下来可以做什么")
    md.append("")
    md.append("1. **重启后端**：`RagService` 会自动扫描新增的 `lore/stories/*.md` 71 个场景切片并建索引（如果是 force-rebuild-on-startup=true 模式，开发期自动）。")
    md.append("2. **观察日志**：搜索 `已注入 RAG 上下文, roleCode=shu` 确认每轮命中长度。")
    md.append("3. **跑 Win 31B 蒸馏档案**：作为人工对照基准；如果质量明显更好，可以把 `persona_distilled.md` 直接以 lore 形式塞进去（不影响现有装配，纯增量），让 LLM 在长文本召回里看到更立体的人物背景。")
    md.append("4. **持续观察对话效果**：如果发现某类问题反复 OOC，把对应剧情切片或新写一条 `memory_cards.jsonl` 条目（权重×1.3）补进去，比改 prompt 更精准。")
    md.append("")

    OUTPUT_DOC.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT_DOC.write_text("\n".join(md) + "\n", encoding="utf-8")
    print(f"已生成：{OUTPUT_DOC.relative_to(WORKSPACE)}（{OUTPUT_DOC.stat().st_size} bytes）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
