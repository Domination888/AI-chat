#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
黍剧情场景切片脚本 (PLAN: 阶段2)
================================

把 data/raw/shu/stories/huai-shu-li/*.md 切成场景片段，写到
backend/src/main/resources/personas/shu/lore/stories/，
让后端 RagService 启动时自动建库，prompt 装配时按场景检索注入。

切片规则：
  - 按"黍连续出场的对话片段"切：找到第一个 **黍**: 出现 → 往前回退 1~2 句上下文
    → 往后跟随直到出现连续 ≥3 个非黍发言或段落空行 → 形成一个场景片段
  - 每片 200~450 字（RagService 默认 chunk_size=500，单片正好填满不需要二次切）
  - 太短（<60 字）的片段丢弃
  - 输出格式：第一行 `# {关卡}/{section} · 场景{编号}`，之后是原文

输出：
  backend/src/main/resources/personas/shu/lore/stories/
    hs-1_赴大荒__01_命数手相_左乐登场.md
    hs-1_赴大荒__02_...
    ...
  并打印总数 + 平均长度方便核对。

依赖：仅 Python 标准库。
"""

from __future__ import annotations
import re
import sys
from pathlib import Path

WORKSPACE = Path(__file__).resolve().parent.parent
INPUT_DIR  = WORKSPACE / "data" / "raw" / "shu" / "stories" / "huai-shu-li"
OUTPUT_DIR = WORKSPACE / "backend" / "src" / "main" / "resources" / "personas" / "shu" / "lore" / "stories"

# 切片参数
SHU_NAME           = "黍"
MIN_CHARS          = 60      # 太短的片段（一两句寒暄）跳过
TARGET_MAX_CHARS   = 450     # 单片目标上限：超过就尝试在段落分界提前收尾
HARD_MAX_CHARS     = 700     # 真正硬上限：超过就强切
CONTEXT_BEFORE     = 2       # 黍首次出场前往前抓几句作为上下文
TAIL_NON_SHU       = 3       # 黍最后一句后再跟几句非黍发言就停止
MIN_GAP_NON_SHU    = 3       # 连续多少句非黍发言视为切片结束

# 行类型
SPEAKER_LINE = re.compile(r"^\*\*([^*]+)\*\*：\s*(.+?)\s*$")
NARRATION_LINE = re.compile(r"^>\s*(.+?)\s*$")
SECTION_HEADER = re.compile(r"^##\s+(.+?)\s*$")
WIKI_NOISE = re.compile(r"剧情模拟器|Widget:|文本数据|\{\{|\}\}")


def parse_story(md_text: str) -> list[dict]:
    """把整篇剧情解析成结构化行列表。"""
    lines = []
    current_section = "主剧情"
    for raw in md_text.splitlines():
        line = raw.rstrip()
        if not line.strip():
            continue
        m = SECTION_HEADER.match(line)
        if m:
            current_section = m.group(1).strip()
            continue
        # 跳过 wiki 噪声
        if WIKI_NOISE.search(line):
            continue
        m = SPEAKER_LINE.match(line)
        if m:
            spk = m.group(1).strip()
            text = m.group(2).strip()
            lines.append({"section": current_section, "type": "speech",
                          "speaker": spk, "text": text, "raw": line})
            continue
        m = NARRATION_LINE.match(line)
        if m:
            lines.append({"section": current_section, "type": "narration",
                          "speaker": "", "text": m.group(1).strip(), "raw": line})
            continue
        # 普通段落（标题/描述）也保留，避免漏掉无引号的旁白
        if not line.startswith("#") and not line.startswith(">"):
            lines.append({"section": current_section, "type": "narration",
                          "speaker": "", "text": line.strip(), "raw": "> " + line.strip()})
    return lines


def slice_scenes(lines: list[dict]) -> list[dict]:
    """按"黍出场"切片：返回 [{section, scene_id, speakers, length, body_lines}]。"""
    scenes = []
    n = len(lines)
    i = 0
    scene_idx = 0
    while i < n:
        # 找下一个黍发言
        j = i
        while j < n and not (lines[j]["type"] == "speech" and lines[j]["speaker"] == SHU_NAME):
            j += 1
        if j >= n:
            break

        # 找该片段的起点：黍发言往前回退 CONTEXT_BEFORE 句（不跨 section）
        section = lines[j]["section"]
        start = j
        ctx_taken = 0
        k = j - 1
        while k >= i and ctx_taken < CONTEXT_BEFORE and lines[k]["section"] == section:
            start = k
            ctx_taken += 1
            k -= 1

        # 找该片段的终点：从黍发言往后，遇到连续 MIN_GAP_NON_SHU 句非黍发言就在最后一句黍发言后再多收 TAIL_NON_SHU 句
        end = j
        last_shu_idx = j
        non_shu_run = 0
        k = j + 1
        while k < n and lines[k]["section"] == section:
            cur_len = sum(len(l["text"]) for l in lines[start:k+1])
            if cur_len > HARD_MAX_CHARS:
                break
            if lines[k]["type"] == "speech" and lines[k]["speaker"] == SHU_NAME:
                last_shu_idx = k
                non_shu_run = 0
                end = k
            else:
                non_shu_run += 1
                if non_shu_run >= MIN_GAP_NON_SHU:
                    end = min(last_shu_idx + TAIL_NON_SHU, k)
                    break
                else:
                    end = k
            k += 1
        # 没触发非黍停止条件，end 已经是 k-1 或 j
        if end < last_shu_idx:
            end = min(last_shu_idx + TAIL_NON_SHU, n - 1)
            while end > last_shu_idx and lines[end]["section"] != section:
                end -= 1

        body = lines[start:end+1]
        text_len = sum(len(l["text"]) for l in body)

        if text_len >= MIN_CHARS:
            speakers = set(l["speaker"] for l in body if l["type"] == "speech" and l["speaker"])
            scene_idx += 1
            scenes.append({
                "section": section,
                "scene_id": scene_idx,
                "speakers": sorted(speakers),
                "length": text_len,
                "body": body,
            })

        i = end + 1
    return scenes


def derive_scene_title(scene: dict, max_len: int = 18) -> str:
    """从场景里抽一个短标题：取第一句黍的话头 + 关键同场人物。"""
    shu_lines = [l["text"] for l in scene["body"] if l["speaker"] == SHU_NAME]
    head = shu_lines[0] if shu_lines else (scene["body"][0]["text"] if scene["body"] else "")
    head = re.sub(r"[，。！？……\.\?!,;；：:\s].*", "", head)
    head = head[:max_len]
    others = [s for s in scene["speakers"] if s and s != SHU_NAME]
    if others:
        # 优先取有名有姓的人，避免"惊奇的职农"这种泛指
        priority = ["左乐", "年", "夕", "万顷", "小满", "禾生", "重岳", "令", "颉",
                    "塞维", "塞弗林", "老乡长", "质朴的农人", "年迈的女性", "懵懂的少女"]
        named = [s for s in priority if s in others]
        if named:
            head += "_" + named[0]
        elif others:
            head += "_" + others[0]
    head = re.sub(r"[^\w\u4e00-\u9fff_]", "", head)
    return head or "片段"


def render_scene_md(stage_code: str, stage_title: str, scene: dict) -> str:
    """渲染单个场景片段为 markdown。"""
    title = derive_scene_title(scene)
    speakers_str = "、".join(scene["speakers"]) if scene["speakers"] else "（旁白）"
    head = (f"# {stage_code} {stage_title} · {scene['section']} · 场景{scene['scene_id']:02d}「{title}」\n\n"
            f"> 同场人物：{speakers_str}\n\n")
    body = []
    for l in scene["body"]:
        if l["type"] == "speech":
            body.append(f"{l['speaker']}：{l['text']}")
        else:
            body.append(f"（{l['text']}）")
    return head + "\n".join(body) + "\n"


def safe_filename(s: str) -> str:
    s = re.sub(r"[^\w\u4e00-\u9fff_\-]", "_", s)
    s = re.sub(r"_+", "_", s).strip("_")
    return s[:60] or "scene"


def parse_stage_meta(filename: str) -> tuple[str, str]:
    """从 hs-1_赴大荒.md 推断 (HS-1, 赴大荒)。"""
    stem = filename.rsplit(".", 1)[0]
    parts = stem.split("_", 1)
    code = parts[0].upper().replace("-", "-")
    title = parts[1] if len(parts) > 1 else ""
    return code, title


def main() -> int:
    if not INPUT_DIR.exists():
        print(f"[err] 输入目录不存在：{INPUT_DIR}", file=sys.stderr)
        return 1

    # 清理旧输出，避免遗留废文件
    if OUTPUT_DIR.exists():
        for f in OUTPUT_DIR.glob("*.md"):
            f.unlink()
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

    md_files = sorted(p for p in INPUT_DIR.glob("hs*.md"))
    print(f"扫描到 {len(md_files)} 个剧情文件，输出到 {OUTPUT_DIR.relative_to(WORKSPACE)}/")

    total_scenes = 0
    total_chars = 0
    per_stage = []
    for src in md_files:
        text = src.read_text(encoding="utf-8")
        stage_code, stage_title = parse_stage_meta(src.name)
        lines = parse_story(text)
        scenes = slice_scenes(lines)
        if not scenes:
            print(f"  [skip] {src.name}：未检出黍出场场景")
            continue
        for sc in scenes:
            out_md = render_scene_md(stage_code, stage_title, sc)
            short_title = derive_scene_title(sc)
            fname = f"{stage_code.lower()}__{sc['scene_id']:02d}_{safe_filename(short_title)}.md"
            (OUTPUT_DIR / fname).write_text(out_md, encoding="utf-8")
            total_chars += sc["length"]
        per_stage.append((src.name, len(scenes), sum(s["length"] for s in scenes)))
        total_scenes += len(scenes)

    print(f"\n=== 切片统计 ===")
    print(f"{'关卡文件':<32} {'场景数':>6} {'总字数':>8} {'平均':>6}")
    for name, n, c in per_stage:
        avg = c // n if n else 0
        print(f"{name:<32} {n:>6} {c:>8} {avg:>6}")
    print(f"{'合计':<32} {total_scenes:>6} {total_chars:>8} "
          f"{(total_chars // total_scenes) if total_scenes else 0:>6}")
    print(f"\n输出文件清单：")
    for f in sorted(OUTPUT_DIR.glob("*.md")):
        print(f"  {f.relative_to(WORKSPACE)}  ({f.stat().st_size} bytes)")

    return 0


if __name__ == "__main__":
    sys.exit(main())