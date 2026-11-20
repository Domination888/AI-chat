#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
黍角色档案蒸馏脚本 (PLAN: 阶段1)
================================

map-reduce 两阶段：
  1) Map：对每个原始素材（剧情/档案/语音）单独喂给 LLM，按统一 JSON schema 抽取
  2) Reduce：把所有 JSON 片段拼起来，让 LLM 合并去重，输出最终 Markdown 档案

模型走 OpenAI 兼容接口（LM Studio / vLLM / Ollama / DashScope 都可）。
通过环境变量切换：
  LLM_BASE_URL   默认 http://127.0.0.1:1234/v1   （Mac 本机或 Win 映射过来）
  LLM_MODEL      默认 gemma4-e4b
  LLM_CTX        默认 8192
  LLM_TIMEOUT    默认 600（秒，单次请求）

用法：
    # 只跑 map（单文件可在 SOURCES 里增删）
    python3 scripts/distill_shu_persona.py map

    # 只跑 reduce（基于已有 map 结果）
    python3 scripts/distill_shu_persona.py reduce

    # 一次跑完
    python3 scripts/distill_shu_persona.py all

    # 切到 Win 31B 跑
    LLM_BASE_URL=http://192.168.124.2:1234/v1 LLM_MODEL=gemma-4-31b-it \
        python3 scripts/distill_shu_persona.py all

输出：
    data/processed/shu/persona_distillation/<model_tag>/
        map_*.json              # 每个素材的抽取片段
        merged_input.txt        # reduce 阶段输入（拼接版）
        persona_distilled.md    # 最终人物档案

依赖：仅 Python 标准库（urllib + json）。
"""

from __future__ import annotations
import json
import os
import re
import sys
import time
import urllib.request
import urllib.error
from pathlib import Path

if __package__:
    from . import distill_shu_prompts as prompts
else:
    import distill_shu_prompts as prompts

# ============================ 配置 ============================

WORKSPACE = Path(__file__).resolve().parent.parent
LLM_BASE_URL = os.environ.get("LLM_BASE_URL", "http://127.0.0.1:1234/v1").rstrip("/")
LLM_MODEL    = os.environ.get("LLM_MODEL", "gemma4-e4b")
LLM_CTX      = int(os.environ.get("LLM_CTX", "8192"))
LLM_TIMEOUT  = int(os.environ.get("LLM_TIMEOUT", "600"))
LLM_TEMP     = float(os.environ.get("LLM_TEMP", "0.2"))
# 单次提交给 LLM 的最大原文字符数（粗略按 1 token ≈ 1.5 中文字估计，留出 prompt+输出余量）
# ctx_len=8192 时：留 1500 给 prompt，留 1500 给输出，剩 ~5000 token ≈ 7500 字
MAX_INPUT_CHARS = int(os.environ.get("MAX_INPUT_CHARS", str(int(LLM_CTX * 1.0))))

MODEL_TAG = re.sub(r"[^\w.-]+", "_", f"{LLM_MODEL}__{LLM_BASE_URL.split('//')[-1].split('/')[0]}")
OUTPUT_DIR = WORKSPACE / "data" / "processed" / "shu" / "persona_distillation" / MODEL_TAG

# ============================ 素材清单 ============================

# (素材标识, 文件相对路径, 素材类型)
SOURCES: list[tuple[str, str, str]] = [
    # 角色档案 / 语音 —— 高密度信息，必须包含
    ("profile",  ".local/personal/personas/shu/lore/profile.md",  "干员档案"),
    ("voice",    ".local/personal/personas/shu/lore/voice.md",    "干员语音"),
    ("module",   ".local/personal/personas/shu/lore/module.md",   "干员模组"),
    ("items",    ".local/personal/personas/shu/lore/items.md",    "相关道具"),
    # 怀黍离活动剧情 —— 12 个文件
    ("hs_st1",   "data/raw/shu/stories/huai-shu-li/hs-st-1_禾下梦.md",        "活动剧情"),
    ("hs_1",     "data/raw/shu/stories/huai-shu-li/hs-1_赴大荒.md",          "活动剧情"),
    ("hs_2",     "data/raw/shu/stories/huai-shu-li/hs-2_祭神农.md",          "活动剧情"),
    ("hs_3",     "data/raw/shu/stories/huai-shu-li/hs-3_早芒种.md",          "活动剧情"),
    ("hs_4",     "data/raw/shu/stories/huai-shu-li/hs-4_话桑麻.md",          "活动剧情"),
    ("hs_5",     "data/raw/shu/stories/huai-shu-li/hs-5_纺绫罗.md",          "活动剧情"),
    ("hs_st2",   "data/raw/shu/stories/huai-shu-li/hs-st-2_织锦缎.md",       "活动剧情"),
    ("hs_6",     "data/raw/shu/stories/huai-shu-li/hs-6_卷赤霞.md",          "活动剧情"),
    ("hs_7",     "data/raw/shu/stories/huai-shu-li/hs-7_梦四时.md",          "活动剧情"),
    ("hs_8",     "data/raw/shu/stories/huai-shu-li/hs-8_种因.md",            "活动剧情"),
    ("hs_9",     "data/raw/shu/stories/huai-shu-li/hs-9_得果.md",            "活动剧情"),
    ("hs_st3",   "data/raw/shu/stories/huai-shu-li/hs-st-3_彻风雨.md",       "活动剧情"),
]

# ============================ HTTP 调用 ============================

def call_llm(messages: list[dict], temperature: float = LLM_TEMP,
             max_tokens: int = 4096, json_mode: bool = False) -> str:
    """调用 OpenAI 兼容的 /chat/completions，返回 content 字符串。"""
    payload = {
        "model": LLM_MODEL,
        "messages": messages,
        "temperature": temperature,
        "max_tokens": max_tokens,
        "stream": False,
    }
    if json_mode:
        # LM Studio 不接受 type=json_object，只支持 json_schema 或 text。
        # 这里走 text 模式 + prompt 强约束 + 解析端 lenient parsing，兼容性最好。
        payload["response_format"] = {"type": "text"}

    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(
        f"{LLM_BASE_URL}/chat/completions",
        data=data,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=LLM_TIMEOUT) as resp:
            body = resp.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as e:
        err_body = e.read().decode("utf-8", errors="replace")[:500]
        raise RuntimeError(f"HTTP {e.code} {e.reason}: {err_body}") from e
    except Exception as e:
        raise RuntimeError(f"LLM 调用失败: {e}") from e

    elapsed = time.time() - t0
    obj = json.loads(body)
    content = obj["choices"][0]["message"]["content"]
    usage = obj.get("usage", {})
    print(f"      [llm] {elapsed:.1f}s | prompt={usage.get('prompt_tokens','?')} "
          f"completion={usage.get('completion_tokens','?')}", flush=True)
    return content


# ============================ JSON 解析容错 ============================

def parse_json_lenient(text: str) -> dict:
    """模型偶尔会带前后缀，做容错提取。"""
    text = text.strip()
    if text.startswith("```"):
        # 去掉 ```json ... ``` 包裹
        text = re.sub(r"^```(?:json)?\s*", "", text)
        text = re.sub(r"\s*```$", "", text)
    # 截取第一个 { 到最后一个 }
    s = text.find("{")
    e = text.rfind("}")
    if s == -1 or e == -1:
        raise ValueError(f"未找到 JSON 对象，原始返回前 200 字：{text[:200]}")
    candidate = text[s:e+1]
    return json.loads(candidate)


# ============================ Map 阶段 ============================

def truncate_content(text: str, max_chars: int) -> str:
    """素材太长时按段切到上限附近的换行处。"""
    if len(text) <= max_chars:
        return text
    cut = text[:max_chars]
    # 回退到最近一个段落分隔，避免半句截断
    last_para = cut.rfind("\n\n")
    if last_para > max_chars * 0.7:
        cut = cut[:last_para]
    return cut + "\n\n[…素材后续部分被截断…]"


def map_one(source_id: str, source_path: Path, source_type: str) -> dict:
    """对一份素材跑一次抽取，返回 JSON dict。"""
    if not source_path.exists():
        print(f"  [skip] {source_id}: 文件不存在 {source_path}")
        return {}

    raw = source_path.read_text(encoding="utf-8")
    content = truncate_content(raw, MAX_INPUT_CHARS)
    print(f"  [map] {source_id} ({source_type}, {len(raw)} 字 -> 实际 {len(content)} 字)", flush=True)

    user_msg = prompts.MAP_USER_TEMPLATE.format(
        source_id=source_id,
        source_type=source_type,
        source_path=str(source_path.relative_to(WORKSPACE)),
        content=content,
    )
    messages = [
        {"role": "system", "content": prompts.MAP_SYSTEM_PROMPT},
        {"role": "user",   "content": user_msg},
    ]

    raw_out = call_llm(messages, temperature=LLM_TEMP, max_tokens=4096, json_mode=True)
    try:
        result = parse_json_lenient(raw_out)
    except Exception as e:
        print(f"      [warn] JSON 解析失败：{e}", file=sys.stderr)
        # 留下原始文本以便人工修复
        bad_path = OUTPUT_DIR / f"map_{source_id}.bad.txt"
        bad_path.write_text(raw_out, encoding="utf-8")
        print(f"      [info] 已保存原始返回到 {bad_path}", file=sys.stderr)
        return {}

    # 强制 source_id 字段一致
    result["source_id"] = source_id
    return result


def run_map_phase() -> list[dict]:
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    fragments: list[dict] = []
    for source_id, rel_path, source_type in SOURCES:
        out_path = OUTPUT_DIR / f"map_{source_id}.json"
        # 已经有结果就跳过（用 RESUME=0 强制重跑）
        if out_path.exists() and os.environ.get("RESUME", "1") == "1":
            print(f"  [skip] {source_id}: 已存在 {out_path.name}（设 RESUME=0 强制重跑）")
            try:
                fragments.append(json.loads(out_path.read_text(encoding="utf-8")))
            except Exception:
                pass
            continue

        source_path = WORKSPACE / rel_path
        try:
            frag = map_one(source_id, source_path, source_type)
        except Exception as e:
            print(f"  [err] {source_id}: {e}", file=sys.stderr)
            time.sleep(2.0)
            continue

        if frag:
            out_path.write_text(json.dumps(frag, ensure_ascii=False, indent=2), encoding="utf-8")
            fragments.append(frag)
            print(f"      [ok] -> {out_path.name}")
        time.sleep(0.5)

    return fragments


def collect_section_data(fragments: list[dict], schema_keys: list[str]) -> list[dict]:
    """从所有 map 片段中只挑出指定 schema_keys 的内容，按 source_id 分组。"""
    out = []
    for f in fragments:
        sid = f.get("source_id", "?")
        bucket = {"source_id": sid}
        any_data = False
        for k in schema_keys:
            v = f.get(k) or []
            if v:
                bucket[k] = v
                any_data = True
        if any_data:
            out.append(bucket)
    return out


def run_reduce_phase(fragments: list[dict]) -> str:
    """按章节分批让 LLM 写，每批的输入只包含该章节相关字段，规避 ctx_len 限制。"""
    if not fragments:
        # 兜底：从磁盘加载
        for f in sorted(OUTPUT_DIR.glob("map_*.json")):
            try:
                fragments.append(json.loads(f.read_text(encoding="utf-8")))
            except Exception:
                pass
    if not fragments:
        raise RuntimeError("没有可用的 map 片段，先运行 map 阶段")

    print(f"\n[reduce] 共 {len(fragments)} 个 map 片段，按 {len(SECTION_PLAN)} 个章节分批生成")

    # 缓存目录：每个章节单独一个文件，便于断点续跑和人工检查
    sections_dir = OUTPUT_DIR / "sections"
    sections_dir.mkdir(parents=True, exist_ok=True)

    section_outputs: list[tuple[str, str]] = []  # (section_id, md)

    for sec_id, schema_keys, sec_title, sec_instruction in SECTION_PLAN:
        sec_path = sections_dir / f"{sec_id}.md"
        if sec_path.exists() and os.environ.get("RESUME", "1") == "1":
            print(f"  [skip] 章节 {sec_title}：已存在 {sec_path.name}（设 RESUME=0 强制重写）")
            section_outputs.append((sec_id, sec_path.read_text(encoding="utf-8")))
            continue

        print(f"  [section] 写作章节：{sec_title}")

        if sec_id == "guide":
            # 第 9 节用前面已写好的章节作为输入
            prior = "\n\n".join(md for _, md in section_outputs)
            user_msg = prompts.GUIDE_USER_TEMPLATE.format(
                section_title=sec_title,
                section_instruction=sec_instruction,
                prior_sections=prior[:int(MAX_INPUT_CHARS * 0.7)],  # 保护性截断
            )
        else:
            data = collect_section_data(fragments, schema_keys)
            data_str = json.dumps(data, ensure_ascii=False, indent=2)
            # 单章节也可能太长（特别是 personality_traits 几十条），按上限切
            if len(data_str) > MAX_INPUT_CHARS * 0.85:
                # 简单截断：从尾部按片段砍直到合规
                while data and len(data_str) > MAX_INPUT_CHARS * 0.85:
                    data.pop()
                    data_str = json.dumps(data, ensure_ascii=False, indent=2)
                print(f"      [warn] 章节数据过长，已截断为前 {len(data)} 个 source 的内容")
            user_msg = prompts.SECTION_USER_TEMPLATE.format(
                section_title=sec_title,
                section_instruction=sec_instruction,
                section_data=data_str,
            )
            print(f"      [info] 输入长度 {len(user_msg)} 字")

        messages = [
            {"role": "system", "content": prompts.SECTION_SYSTEM_PROMPT},
            {"role": "user",   "content": user_msg},
        ]

        try:
            md = call_llm(messages, temperature=LLM_TEMP, max_tokens=2500, json_mode=False)
        except Exception as e:
            print(f"      [err] 章节 {sec_title} 生成失败：{e}", file=sys.stderr)
            continue

        md = md.strip()
        if md.startswith("```"):
            md = re.sub(r"^```(?:markdown|md)?\s*", "", md)
            md = re.sub(r"\s*```$", "", md)

        sec_path.write_text(md + "\n", encoding="utf-8")
        section_outputs.append((sec_id, md))
        print(f"      [ok] -> {sec_path.name}（{len(md)} 字）")
        time.sleep(0.3)

    # 拼装成最终档案
    header = "# 黍 · 人物档案（蒸馏版）\n\n"
    header += "> 自动生成自 `scripts/distill_shu_persona.py`，材料来源：黍干员档案+语音+怀黍离活动 12 篇剧情。\n"
    header += f"> 生成模型：{LLM_MODEL} @ {LLM_BASE_URL}\n\n"
    body = "\n\n".join(md for _, md in section_outputs)
    final_md = header + body + "\n"

    out_path = OUTPUT_DIR / "persona_distilled.md"
    out_path.write_text(final_md, encoding="utf-8")
    print(f"\n[reduce] 终稿已写入 {out_path}（{len(final_md)} 字，共 {len(section_outputs)} 个章节）")
    return final_md


# ============================（旧的整体合并函数已废弃，保留为参考工具）

def compact_fragments(fragments: list[dict]) -> list[dict]:
    """在合并 JSON 太长时，把空数组字段都删掉，节省 token。"""
    keys = ["identity_evidences", "personality_traits", "speech_style",
            "relationships", "key_events", "values_and_bottomlines",
            "signature_lines", "behaviors_and_quirks"]
    out = []
    for f in fragments:
        clean = {"source_id": f.get("source_id", "?")}
        for k in keys:
            v = f.get(k) or []
            if v:
                clean[k] = v
        out.append(clean)
    return out


# ============================ 主流程 ============================

def main() -> int:
    cmd = sys.argv[1] if len(sys.argv) > 1 else "all"
    if cmd not in {"map", "reduce", "all"}:
        print("用法：python3 distill_shu_persona.py [map|reduce|all]", file=sys.stderr)
        return 1

    print(f"== 蒸馏脚本 ==")
    print(f"  WORKSPACE      : {WORKSPACE}")
    print(f"  LLM_BASE_URL   : {LLM_BASE_URL}")
    print(f"  LLM_MODEL      : {LLM_MODEL}")
    print(f"  LLM_CTX        : {LLM_CTX}")
    print(f"  MAX_INPUT_CHARS: {MAX_INPUT_CHARS}")
    print(f"  OUTPUT_DIR     : {OUTPUT_DIR}")
    print()

    fragments: list[dict] = []
    if cmd in {"map", "all"}:
        print(">>> Phase 1: MAP")
        fragments = run_map_phase()
        print(f"\n  map 阶段完成，共 {len(fragments)} 个片段")

    if cmd in {"reduce", "all"}:
        print("\n>>> Phase 2: REDUCE")
        run_reduce_phase(fragments)

    print("\n全部完成。")
    return 0


if __name__ == "__main__":
    sys.exit(main())