#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
批量抓取明日方舟"怀黍离"活动剧情，使用 PRTS Wiki 的 action=raw 接口拿到 wikitext，
然后解析 [name="角色"]台词 / 旁白行 输出 Markdown。

用法:
    python3 scripts/fetch_huai_shu_li_stories.py

输出:
    data/raw/shu/stories/huai-shu-li/hs-*.md
"""

from __future__ import annotations
import re
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

OUTPUT_DIR = Path("data/raw/shu/stories/huai-shu-li")

# 关卡清单：(关卡代号, 关卡名, 路径变体)
STAGES = [
    ("HS-ST-1", "禾下梦",   ["NBT"]),
    ("HS-1",    "赴大荒",   ["BEG", "END"]),
    ("HS-2",    "祭神农",   ["BEG", "END"]),
    ("HS-3",    "早芒种",   ["BEG", "END"]),
    ("HS-4",    "话桑麻",   ["BEG", "END"]),
    ("HS-5",    "纺绫罗",   ["BEG", "END"]),
    ("HS-ST-2", "织锦缎",   ["NBT"]),
    ("HS-6",    "卷赤霞",   ["BEG", "END"]),
    ("HS-7",    "梦四时",   ["BEG", "END"]),
    ("HS-8",    "种因",     ["BEG", "END"]),
    ("HS-9",    "得果",     ["BEG", "END"]),
    ("HS-ST-3", "彻风雨",   ["NBT"]),
]

HEADERS = {"User-Agent": "Mozilla/5.0 (research; persona-corpus)"}

# 解析 [name="xxx"]文本 这种行的正则
NAME_LINE = re.compile(r'\[name="([^"]+)"\]\s*(.+?)\s*$')
# 解析 [multiline(name="xxx")]文本 同样视为带说话人
MULTILINE_NAME = re.compile(r'\[multiline\(name="([^"]+)"\)\]\s*(.+?)\s*$')
# 解析 [Sticker(... text="xxx" ...)]
STICKER_TEXT = re.compile(r'\[Sticker\([^\)]*?text="([^"]+)"', re.IGNORECASE)
# 匹配剧本指令开头的行（要丢弃）
DIRECTIVE_LINE = re.compile(r'^\s*\[(?:Header|Blocker|Dialog|stopmusic|playMusic|PlayMusic|PlaySound|Background|charslot|Image|ImageTween|backgroundTween|CameraEffect|CameraShake|SoundVolume|StopSound|Delay|delay|Sticker|musicvolume|playsound|stopsound|HEADER|InputController|FadeFix|Predicate|Sandbox|If|Else|EndIf|EndPredicate|Branch|PopupDialog|Input|Decision|Effect|effect)\b', re.IGNORECASE)
# 匹配纯指令模板边界字符
WIKI_NOISE = re.compile(r'^\s*\{\{.*?\}\}\s*$|^\s*\|.*$|^\s*\}\}\s*$|^\s*<.+?>\s*$')


def http_get(url: str, retries: int = 3, sleep_s: float = 1.5) -> str:
    last_err = None
    for i in range(retries):
        try:
            req = urllib.request.Request(url, headers=HEADERS)
            with urllib.request.urlopen(req, timeout=30) as resp:
                return resp.read().decode("utf-8", errors="replace")
        except Exception as e:
            last_err = e
            print(f"  [warn] {url} 第 {i+1}/{retries} 次失败: {e}", file=sys.stderr)
            time.sleep(sleep_s * (i + 1))
    raise RuntimeError(f"抓取失败 {url}: {last_err}")


def build_raw_url(stage_code: str, stage_name: str, variant: str) -> str:
    """构造 wikitext 原文 URL，例如 /index.php?title=HS-1_赴大荒/BEG&action=raw 。"""
    title = f"{stage_code}_{stage_name}/{variant}"
    return f"https://prts.wiki/index.php?title={urllib.parse.quote(title)}&action=raw"


def parse_wikitext(text: str) -> list[str]:
    """
    解析剧情 wikitext，输出 Markdown 行：
    - **角色名**：台词
    - > 旁白
    """
    lines: list[str] = []
    for raw_line in text.splitlines():
        line = raw_line.strip()
        if not line:
            continue
        # 跳过 wiki 模板和 HTML 边界
        if WIKI_NOISE.match(line):
            continue
        # 剧情指令开头：跳过
        if DIRECTIVE_LINE.match(line):
            # 但 Sticker 里可能藏着台词
            m = STICKER_TEXT.search(line)
            if m:
                txt = m.group(1).replace("\\n", " ").strip()
                if txt:
                    lines.append(f"> {txt}")
            continue
        # [name="角色"]内容
        m = NAME_LINE.match(line)
        if m:
            speaker = m.group(1).strip()
            text = m.group(2).strip()
            # 跳过空台词
            if text:
                lines.append(f"**{speaker}**：{text}")
            continue
        # [multiline(name="角色")]内容
        m = MULTILINE_NAME.match(line)
        if m:
            speaker = m.group(1).strip()
            text = m.group(2).strip()
            if text:
                lines.append(f"**{speaker}**：{text}")
            continue
        # 剩下：可能是纯叙述/旁白，但需要排除残留指令片段
        if line.startswith("[") and line.endswith("]"):
            continue
        # 否则当做叙述/独立旁白行
        # 但过滤掉太短的纯特效（如 "......" 单独一行是叙事性的，保留）
        lines.append(f"> {line}")
    return lines


def save_stage(stage_code: str, stage_name: str, variants: list[str]) -> None:
    safe_name = f"{stage_code.lower()}_{stage_name}.md"
    out_path = OUTPUT_DIR / safe_name
    parts: list[str] = []
    parts.append(f"# {stage_code} {stage_name}\n\n")
    parts.append(f"> 来源：PRTS Wiki (action=raw wikitext)\n")
    is_pure = (variants == ["NBT"])
    parts.append(f"> 类型：{'纯剧情' if is_pure else '战斗（行动前 + 行动后）'}\n\n")

    total_lines = 0
    for v in variants:
        url = build_raw_url(stage_code, stage_name, v)
        print(f"[fetch] {stage_code} {v} -> {url}")
        try:
            text = http_get(url)
        except Exception as e:
            print(f"  [err] 抓取失败: {e}", file=sys.stderr)
            parts.append(f"## {v}\n\n_抓取失败：{e}_\n\n")
            continue

        # 内容很短可能是 404 / 重定向到空页
        if len(text) < 100:
            parts.append(f"## {v}\n\n_页面为空或不存在 ({len(text)} 字节)_\n\n")
            print(f"  [warn] 页面过短：{len(text)} 字节")
            continue

        lines = parse_wikitext(text)
        if not lines:
            parts.append(f"## {v}\n\n_未提取到剧情行_\n\n")
            print(f"  [warn] 未提取到剧情行")
            continue

        section_title = {"NBT": "主剧情", "BEG": "行动前", "END": "行动后"}.get(v, v)
        parts.append(f"## {section_title}\n\n")
        parts.append("\n\n".join(lines))
        parts.append("\n\n")
        total_lines += len(lines)
        time.sleep(0.8)

    out_path.write_text("".join(parts), encoding="utf-8")
    size_kb = out_path.stat().st_size // 1024
    print(f"  [ok] 已写入 {out_path}（{size_kb} KB, {total_lines} 行）")


def main() -> int:
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    print(f"输出目录：{OUTPUT_DIR.resolve()}\n")
    for code, name, variants in STAGES:
        if code == "HS-ST-1":
            print(f"[skip] {code} {name}（已存在手工版本）\n")
            continue
        save_stage(code, name, variants)
        print()
    print("全部完成。")
    return 0


if __name__ == "__main__":
    sys.exit(main())