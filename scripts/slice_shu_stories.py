#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
黍剧情 RAG 原文切片脚本（保护模式）
================================

把 data/raw/shu/stories/huai-shu-li/*.md 切成适合 RAG 检索的剧情节点，写到：
    backend/src/main/resources/personas/shu/lore/stories/

注意：当前仓库中的 lore/stories/ 是人工理解后的剧情摘要语料，不是本脚本生成的
raw 台词切片。为避免误覆盖，脚本默认只提示用途；如确实需要生成原文切片，
显式传入 --write-raw-fallback。

设计目标：
  - 覆盖完整剧情，而不是只截取黍直接说话的片段。
  - 每个文件是一个相对完整的剧情节点，尽量落在后端 rag.chunk-size=500 附近。
  - 节点顶部补充章节、同场人物、黍相关性、关键词，提升 embedding 召回。
  - 正文保持原剧情顺序，便于检索命中后直接注入 prompt。

依赖：仅 Python 标准库。
"""

from __future__ import annotations

import re
import sys
from dataclasses import dataclass
from pathlib import Path

WORKSPACE = Path(__file__).resolve().parent.parent
INPUT_DIR = WORKSPACE / "data" / "raw" / "shu" / "stories" / "huai-shu-li"
OUTPUT_DIR = WORKSPACE / "backend" / "src" / "main" / "resources" / "personas" / "shu" / "lore" / "stories"

TARGET_CHARS = 420
SOFT_MAX_CHARS = 560
HARD_MAX_CHARS = 720
MIN_CHARS = 140

SPEAKER_LINE = re.compile(r"^\*\*([^*]+)\*\*：\s*(.+?)\s*$")
NARRATION_LINE = re.compile(r"^>\s*(.+?)\s*$")
SECTION_HEADER = re.compile(r"^##\s+(.+?)\s*$")
WIKI_NOISE = re.compile(r"剧情模拟器|Widget:|文本数据=|^\s*\{\{|\}\}\s*$")

KEY_PEOPLE = [
    "黍", "绩", "年", "夕", "望", "左乐", "小满", "禾生", "老乡长", "神农",
    "令", "重岳", "年迈的女性", "质朴的少年", "顽皮的少女", "沉默的樵夫",
    "万侍郎", "宁辞秋", "荣晚晴", "太傅", "太尉",
]

DOMAIN_TERMS = [
    "大荒城", "神农", "神农祭", "天灾", "移动地块", "十二楼五城", "司岁台",
    "天师府", "职农", "土木天师", "农业天师", "岁兽", "兄弟姐妹",
    "山河百景图", "国祚", "织物", "诡异的织物", "茧", "因果", "农事",
    "庄稼", "开垦", "夏至", "芒种", "桑麻", "纺绫罗", "得果",
]

STAGE_BRIEFS = {
    "hs-st-1": "开篇以远古寻找庄稼、年夕探望黍、大荒城日常和左乐抵达，奠定黍守望农人与土地的核心。",
    "hs-1": "左乐初到大荒城，见黍以农事和日常安顿众人，也逐步理解大荒城的运行方式。",
    "hs-2": "神农祭展开，大荒城灾象初显；黍与老乡长的对话把神农、开垦和漫长守望连在一起。",
    "hs-3": "年、夕与众人分别被卷入异象，小满和沉默樵夫的遭遇揭开织物灾害与绩计划的前奏。",
    "hs-4": "绩回到大荒城，与黍、年、夕、左乐交锋；兄妹分歧集中在是否离开、如何看待因果和人。",
    "hs-5": "黍回望早年开垦与牺牲，灾害逼近现实；绩正式出手，使黍陷入昏睡。",
    "hs-6": "织物灾害扩散，众人组织避难，小满执意寻找沉默樵夫，展现大荒城普通人的互助。",
    "hs-7": "小满以笛声引回沉默樵夫，绩借灾害结茧抽丝，推动山河百景图成形。",
    "hs-8": "年、夕、黍各自面对梦境和记忆；绩说明山河百景图、国祚与大荒城最后一笔。",
    "hs-9": "黍与绩摊牌，众人合力收束灾害；绩离去，黍确认自己仍选择留下。",
    "hs-st-2": "绩与兄长谈棋局、规则、因果和唤醒计划，补足绩行动背后的理念。",
    "hs-st-3": "尾声补足权力、利益与绩的长路，黍与农人对话回到风雨、稻种和传承。",
}


@dataclass
class Line:
    section: str
    type: str
    speaker: str
    text: str


@dataclass
class Scene:
    section: str
    index: int
    lines: list[Line]

    @property
    def text_len(self) -> int:
        return sum(len(line.text) for line in self.lines)


def parse_story(md_text: str) -> list[Line]:
    lines: list[Line] = []
    current_section = "主剧情"

    for raw in md_text.splitlines():
        line = raw.rstrip()
        if not line.strip():
            continue

        section_match = SECTION_HEADER.match(line)
        if section_match:
            current_section = section_match.group(1).strip()
            continue

        if WIKI_NOISE.search(line):
            continue

        speaker_match = SPEAKER_LINE.match(line)
        if speaker_match:
            lines.append(Line(
                section=current_section,
                type="speech",
                speaker=speaker_match.group(1).strip(),
                text=speaker_match.group(2).strip(),
            ))
            continue

        narration_match = NARRATION_LINE.match(line)
        if narration_match:
            text = narration_match.group(1).strip()
            if text.startswith("来源：") or text.startswith("类型："):
                continue
            lines.append(Line(section=current_section, type="narration", speaker="", text=text))
            continue

        if not line.startswith("#") and not line.startswith(">"):
            lines.append(Line(section=current_section, type="narration", speaker="", text=line.strip()))

    return lines


def split_into_scenes(lines: list[Line]) -> list[Scene]:
    scenes: list[Scene] = []
    current: list[Line] = []
    scene_index = 0

    def flush() -> None:
        nonlocal current, scene_index
        if not current:
            return
        if scenes and sum(len(line.text) for line in current) < MIN_CHARS:
            scenes[-1].lines.extend(current)
            current = []
            return
        scene_index += 1
        scenes.append(Scene(section=current[0].section, index=scene_index, lines=current))
        current = []

    for line in lines:
        if current and line.section != current[-1].section:
            flush()

        current.append(line)
        current_len = sum(len(item.text) for item in current)
        is_shu_line = line.speaker == "黍"
        is_key_turn = line.speaker in {"黍", "绩", "年", "夕", "望", "左乐", "小满", "禾生", "老乡长"}
        next_can_break = current_len >= TARGET_CHARS and (not is_shu_line or current_len >= SOFT_MAX_CHARS)

        if current_len >= HARD_MAX_CHARS:
            flush()
        elif next_can_break and is_key_turn:
            flush()
        elif current_len >= SOFT_MAX_CHARS and line.type == "narration":
            flush()

    flush()
    return scenes


def parse_stage_meta(filename: str) -> tuple[str, str, str]:
    stem = filename.rsplit(".", 1)[0]
    code, _, title = stem.partition("_")
    return code.lower(), code.upper(), title


def render_line(line: Line) -> str:
    if line.type == "speech":
        return f"{line.speaker}：{line.text}"
    return f"（{line.text}）"


def first_topic(scene: Scene) -> str:
    for line in scene.lines:
        text = line.text
        if len(text) < 2:
            continue
        text = re.sub(r"[，。！？……\.\?!,;；：:\s].*", "", text)
        text = re.sub(r"[^\w\u4e00-\u9fff_]", "", text)
        if text:
            return text[:18]
    return "剧情节点"


def scene_speakers(scene: Scene) -> list[str]:
    speakers: list[str] = []
    for line in scene.lines:
        if line.speaker and line.speaker not in speakers:
            speakers.append(line.speaker)
    return speakers


def scene_keywords(stage_key: str, scene: Scene) -> list[str]:
    text = "\n".join(render_line(line) for line in scene.lines)
    keywords: list[str] = []

    for term in KEY_PEOPLE + DOMAIN_TERMS:
        if term in text and term not in keywords:
            keywords.append(term)

    if "黍" not in keywords and ("姐姐" in text or "老师" in text):
        keywords.append("黍")
    if "神农" in STAGE_BRIEFS.get(stage_key, "") and "神农" not in keywords:
        keywords.append("神农")

    return keywords[:14]


def relevance_label(scene: Scene) -> str:
    speakers = set(scene_speakers(scene))
    text = "\n".join(line.text for line in scene.lines)
    if "黍" in speakers:
        return "黍直接出场"
    if any(term in text for term in ("黍", "姐姐", "老师", "神农", "大荒城", "山河百景图", "因果")):
        return "黍相关背景"
    return "剧情背景"


def render_scene_md(stage_key: str, stage_code: str, stage_title: str, scene: Scene) -> str:
    speakers = scene_speakers(scene)
    keywords = scene_keywords(stage_key, scene)
    title = first_topic(scene)
    body = "\n".join(render_line(line) for line in scene.lines)
    brief = STAGE_BRIEFS.get(stage_key, "")
    speakers_text = "、".join(speakers) if speakers else "旁白"
    keywords_text = "、".join(keywords) if keywords else "剧情"

    return (
        f"# {stage_code} {stage_title} · {scene.section} · 节点{scene.index:02d}「{title}」\n\n"
        f"> 章节脉络：{brief}\n"
        f"> 同场人物：{speakers_text}\n"
        f"> 黍相关性：{relevance_label(scene)}\n"
        f"> 检索关键词：{keywords_text}\n\n"
        f"{body}\n"
    )


def safe_filename(text: str) -> str:
    text = re.sub(r"[^\w\u4e00-\u9fff_\-]", "_", text)
    text = re.sub(r"_+", "_", text).strip("_")
    return text[:48] or "scene"


def main() -> int:
    if "--write-raw-fallback" not in sys.argv:
        print(
            "当前 lore/stories/ 使用人工理解后的剧情摘要语料；"
            "本脚本只作为 raw 原文切片的备用工具。\n"
            "如确实要覆盖为 raw 切片，请运行："
            "python3 scripts/slice_shu_stories.py --write-raw-fallback"
        )
        return 0

    if not INPUT_DIR.exists():
        print(f"[err] 输入目录不存在：{INPUT_DIR}", file=sys.stderr)
        return 1

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    for old in OUTPUT_DIR.glob("*.md"):
        old.unlink()

    md_files = sorted(INPUT_DIR.glob("hs*.md"))
    print(f"扫描到 {len(md_files)} 个剧情文件，输出到 {OUTPUT_DIR.relative_to(WORKSPACE)}/")

    total_scenes = 0
    total_chars = 0
    per_stage: list[tuple[str, int, int, int]] = []

    for src in md_files:
        stage_key, stage_code, stage_title = parse_stage_meta(src.name)
        lines = parse_story(src.read_text(encoding="utf-8"))
        scenes = split_into_scenes(lines)
        if not scenes:
            print(f"  [skip] {src.name}: 未解析到剧情正文")
            continue

        stage_chars = 0
        for scene in scenes:
            out_md = render_scene_md(stage_key, stage_code, stage_title, scene)
            topic = safe_filename(first_topic(scene))
            fname = f"{stage_key}__{scene.index:02d}_{topic}.md"
            (OUTPUT_DIR / fname).write_text(out_md, encoding="utf-8")
            stage_chars += scene.text_len

        total_scenes += len(scenes)
        total_chars += stage_chars
        per_stage.append((src.name, len(scenes), stage_chars, stage_chars // len(scenes)))

    print("\n=== 切片统计 ===")
    print(f"{'关卡文件':<32} {'节点数':>6} {'正文字数':>8} {'平均':>6}")
    for name, count, chars, avg in per_stage:
        print(f"{name:<32} {count:>6} {chars:>8} {avg:>6}")
    avg_total = total_chars // total_scenes if total_scenes else 0
    print(f"{'合计':<32} {total_scenes:>6} {total_chars:>8} {avg_total:>6}")

    print("\n输出文件清单：")
    for f in sorted(OUTPUT_DIR.glob("*.md")):
        print(f"  {f.relative_to(WORKSPACE)} ({f.stat().st_size} bytes)")

    return 0


if __name__ == "__main__":
    sys.exit(main())
