#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
fetch_prts.py — 从 prts.wiki 拉取干员页面 wikitext，清洗后按人格相关章节切分输出 md

用法:
    python3 scripts/fetch_prts.py 黍 --out data/raw/shu

只保留人格塑造真正需要的章节，丢弃技能数值/材料/模组数据等噪声。
产出的 md 文件直接可作为 RAG 原文语料，也可被 LLM 二次压缩。
"""
import argparse
import json
import os
import re
import sys
import urllib.parse
import urllib.request

API = "https://prts.wiki/api.php"

# 只保留这些章节（人格相关）。章节名是 wikitext 里 == xxx == 的内容。
# 其他干员页面（比如非黍）可能叫"干员资料"而不是"干员档案"，做个兼容。
KEEP_SECTIONS = {
    "干员档案", "干员资料", "档案资料",
    "语音记录", "干员语音",
    "干员密录", "密录",
    "干员信息",        # 包含画师、配音、精英立绘介绍、时装介绍
    "相关道具",        # 信物描述、干员简介
    "干员模组", "模组",  # 模组介绍段落里常有人物故事
}

# 一些子模板字段也是人格描述（从 {{CharinfoV2}} 里提取）
CHARINFO_KEEP_FIELDS = {
    "精英0介绍", "精英2介绍",
    "时装1介绍", "时装2介绍", "时装3介绍",
    "初始场景",
}


def http_get_json(url: str) -> dict:
    req = urllib.request.Request(url, headers={
        "User-Agent": "ai-chat-persona-fetcher/0.1 (local dev)"
    })
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read().decode("utf-8"))


def fetch_wikitext(page: str) -> str:
    q = urllib.parse.urlencode({
        "action": "parse", "page": page, "format": "json", "prop": "wikitext"
    })
    data = http_get_json(f"{API}?{q}")
    if "error" in data:
        raise RuntimeError(data["error"])
    return data["parse"]["wikitext"]["*"]


# 这些字段不想保留（属于数值/物性/引导指令，不是人格内容）
DROP_FIELDS = {
    "性别", "战斗经验", "出身地", "生日", "种族", "身高",
    "矿石病感染情况", "是否感染者",
    "物理强度", "战场机动", "生理耐受", "战术规划", "战斗技巧", "源石技艺适应性",
    "体细胞与源石融合率", "血液源石结晶密度",
    "条件", "权限",
    "档案1", "档案2", "档案3", "档案4", "档案5", "档案6", "档案7", "档案8", "档案9", "档案10",
    "档案1条件", "档案2条件", "档案3条件", "档案4条件", "档案5条件", "档案6条件", "档案7条件", "档案8条件", "档案9条件",
    "语音1标题", "语音2标题", "语音3标题",
    "解锁等级", "解锁信赖",
    "材料消耗", "材料消耗2", "材料消耗3",
    "任务1", "任务2",
    "基础信息",  # 模组里会用，但正文里也有，看情况保留——这里偏保守丢掉
}

# 这些字段保留并作为标题——它们的 value 是剧情/语音/档案正文
KEEP_FIELDS_AS_SECTION = {
    # 人员档案
    "档案1文本", "档案2文本", "档案3文本", "档案4文本",
    "档案5文本", "档案6文本", "档案7文本", "档案8文本", "档案9文本", "档案10文本",
    # 相关道具
    "干员简介", "干员简介补充", "信物用途", "信物描述",
    # 语音记录（任意 语音NN = xxx）——靠动态匹配
    # 模组介绍
    "基础信息",
}


def _is_documentary_template(inner: str) -> bool:
    """判断是否是文档型模板（人员档案/语音/模组/道具）——特征是首行是模板名且包含大量 |xxx= 。"""
    first = inner.split("|", 1)[0].strip()
    return first in {"人员档案", "人员档案set", "相关道具", "模组", "干员语音", "语音记录"}


def _render_voicetable(inner: str) -> str:
    """把 VoiceTable|标题1=xxx|台词1={{VoiceData/word|中文|...}}... 渲染成"### 标题\n台词"。
    注意：内部的 {{VoiceData/word|中文|...}} 已经会被外层循环先行渲染为纯中文台词，
    这里直接按 key 分组拼接即可。"""
    parts = re.split(r"\n\|", inner)
    titles = {}
    lines = {}
    for seg in parts[1:]:
        if "=" not in seg:
            continue
        k, v = seg.split("=", 1)
        k = k.strip()
        v = v.strip()
        m = re.match(r"^标题(\d+)$", k)
        if m:
            titles[m.group(1)] = v
            continue
        m = re.match(r"^台词(\d+)$", k)
        if m:
            lines[m.group(1)] = v
            continue
    # 按序号输出
    out = []
    for idx in sorted(titles.keys(), key=lambda x: int(x)):
        t = titles[idx]
        line = lines.get(idx, "").strip()
        if not line:
            continue
        out.append(f"### {t}\n{line}")
    return "\n\n".join(out)


def _render_documentary_template(inner: str) -> str:
    """把文档型模板渲染为 "## key\nvalue\n" 形式，只保留人格有用字段。"""
    # 按 \n| 切，第一段是模板名，忽略
    parts = re.split(r"\n\|", inner)
    out = []
    for seg in parts[1:]:
        if "=" not in seg:
            continue
        k, v = seg.split("=", 1)
        k = k.strip()
        v = v.strip()
        if not v:
            continue
        if k in DROP_FIELDS:
            continue
        # 动态匹配：语音N、语音N文本、语音N日文 等，保留 "语音N" 和 "语音N文本"
        if re.match(r"^语音\d+$", k):
            out.append(f"\n### {v}")
            continue
        if re.match(r"^语音\d+文本$", k):
            out.append(v)
            continue
        # 档案名（档案1=基础档案）
        if re.match(r"^档案\d+$", k):
            out.append(f"\n### {v}")
            continue
        if k in KEEP_FIELDS_AS_SECTION or re.match(r"^档案\d+文本$", k):
            out.append(v)
            continue
        # 其他未知字段：若 value 看起来像正文（长度 > 20 且包含中文），保留
        if len(v) > 20 and re.search(r"[\u4e00-\u9fff]", v):
            out.append(v)
    return "\n".join(out)


def strip_wiki_markup(text: str) -> str:
    """把 wikitext 清洗成人读的纯文本。"""
    # 去 HTML 注释
    text = re.sub(r"<!--.*?-->", "", text, flags=re.S)
    # 去 ref 标签
    text = re.sub(r"<ref[^>]*>.*?</ref>", "", text, flags=re.S)
    text = re.sub(r"<ref[^/]*/>", "", text)
    text = re.sub(r"</?ref[^>]*>", "", text)
    # 去 section 标签
    text = re.sub(r"<section[^>]*/?>", "", text)
    text = re.sub(r"</section>", "", text)
    # 处理 [[文件:xxx]] 去掉
    text = re.sub(r"\[\[文件:[^\]]+\]\]", "", text)
    text = re.sub(r"\[\[File:[^\]]+\]\]", "", text, flags=re.I)
    # 处理 [[目标|显示]] → 显示； [[目标]] → 目标
    text = re.sub(r"\[\[([^\]\|]+)\|([^\]]+)\]\]", r"\2", text)
    text = re.sub(r"\[\[([^\]]+)\]\]", r"\1", text)

    # 模板处理：区分"文档型"和"修饰型"
    def _render_template(m: re.Match) -> str:
        inner = m.group(1)
        first = inner.split("|", 1)[0].strip()
        # VoiceData/word|语言|文字 —— 只保留中文
        if first in {"VoiceData/word"}:
            parts = inner.split("|", 2)
            if len(parts) >= 3 and parts[1].strip() == "中文":
                return parts[2].strip()
            return ""
        # VoiceTable：渲染成 ### 标题N\n台词N
        if first == "VoiceTable":
            return _render_voicetable(inner)
        if _is_documentary_template(inner):
            return "\n" + _render_documentary_template(inner) + "\n"
        # 修饰型：{{color|xxx|文字}} / {{术语|yyy|文字}}
        if "=" in inner:
            return ""
        parts = inner.split("|")
        return parts[-1] if parts else ""

    # 循环处理最内层模板直到稳定
    pattern = re.compile(r"\{\{([^{}]+)\}\}")
    for _ in range(15):
        new = pattern.sub(_render_template, text)
        if new == text:
            break
        text = new
    # 剩下没处理干净的模板花括号直接删
    text = re.sub(r"\{\{[^}]*\}\}", "", text)
    # HTML 换行
    text = re.sub(r"<br\s*/?>", "\n", text, flags=re.I)
    # 去其他 html 标签
    text = re.sub(r"<[^>]+>", "", text)
    # 连续空行压缩
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip()


def split_sections(wikitext: str) -> dict:
    """按 == xxx == 顶级章节切分。返回 {section_name: raw_wikitext}。"""
    sections = {}
    # 把开头（第一个章节前）作为 "PREAMBLE"
    header_re = re.compile(r"^==\s*([^=].*?)\s*==\s*$", re.M)
    matches = list(header_re.finditer(wikitext))
    if not matches:
        return {"PREAMBLE": wikitext}
    sections["PREAMBLE"] = wikitext[:matches[0].start()]
    for i, m in enumerate(matches):
        name = m.group(1).strip()
        start = m.end()
        end = matches[i + 1].start() if i + 1 < len(matches) else len(wikitext)
        sections[name] = wikitext[start:end]
    return sections


def extract_charinfo_fields(preamble: str) -> str:
    """从 {{CharinfoV2 | key=value ... }} 里抽出人格相关字段。用括号配对找完整模板。"""
    idx = preamble.find("{{CharinfoV2")
    if idx < 0:
        return ""
    depth = 0
    i = idx
    body_start = idx + len("{{CharinfoV2")
    while i < len(preamble) - 1:
        if preamble[i] == "{" and preamble[i+1] == "{":
            depth += 1
            i += 2
            continue
        if preamble[i] == "}" and preamble[i+1] == "}":
            depth -= 1
            if depth == 0:
                break
            i += 2
            continue
        i += 1
    body = preamble[body_start:i]
    entries = re.split(r"\n\|", body)
    out = []
    for e in entries:
        if "=" not in e:
            continue
        k, v = e.split("=", 1)
        k = k.strip()
        v = v.strip()
        if k in CHARINFO_KEEP_FIELDS and v:
            out.append(f"### {k}\n{strip_wiki_markup(v)}")
    return "\n\n".join(out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("page", help="词条名，例如 黍")
    ap.add_argument("--out", required=True, help="输出目录")
    args = ap.parse_args()

    os.makedirs(args.out, exist_ok=True)

    print(f"[1/3] 拉取 wikitext: {args.page}")
    wikitext = fetch_wikitext(args.page)
    # 备份原始
    with open(os.path.join(args.out, "_raw_wikitext.txt"), "w", encoding="utf-8") as f:
        f.write(wikitext)
    print(f"      原始长度: {len(wikitext)} 字符")

    print("[2/3] 切分章节...")
    sections = split_sections(wikitext)
    print(f"      共 {len(sections)} 个章节: {list(sections.keys())}")

    print("[3/3] 过滤 & 清洗 & 写盘...")
    written = []

    # 1) 人设介绍字段（从 CharinfoV2 抽）
    charinfo_text = extract_charinfo_fields(sections.get("PREAMBLE", ""))
    if charinfo_text:
        path = os.path.join(args.out, "01_立绘与场景介绍.md")
        with open(path, "w", encoding="utf-8") as f:
            f.write(f"# {args.page} · 立绘与场景介绍\n\n{charinfo_text}\n")
        written.append(path)

    # 2) 白名单章节
    order_idx = 2
    for name, raw in sections.items():
        if name not in KEEP_SECTIONS:
            continue
        cleaned = strip_wiki_markup(raw)
        if not cleaned or len(cleaned) < 30:
            continue
        safe_name = re.sub(r"[\\/:*?\"<>|]", "_", name)
        path = os.path.join(args.out, f"{order_idx:02d}_{safe_name}.md")
        with open(path, "w", encoding="utf-8") as f:
            f.write(f"# {args.page} · {name}\n\n{cleaned}\n")
        written.append(path)
        order_idx += 1

    # 3) 子页面：语音记录、干员密录等（它们在主页以 {{:黍/xxx}} 形式调用）
    subpages = [
        f"{args.page}/语音记录",
        f"{args.page}/干员密录/1",
    ]
    for sub in subpages:
        try:
            print(f"      拉取子页面: {sub}")
            sub_wikitext = fetch_wikitext(sub)
        except Exception as e:
            print(f"      跳过 {sub}: {e}")
            continue
        cleaned = strip_wiki_markup(sub_wikitext)
        if not cleaned or len(cleaned) < 30:
            continue
        safe_name = re.sub(r"[\\/:*?\"<>|]", "_", sub)
        path = os.path.join(args.out, f"{order_idx:02d}_{safe_name}.md")
        with open(path, "w", encoding="utf-8") as f:
            f.write(f"# {sub}\n\n{cleaned}\n")
        written.append(path)
        order_idx += 1

    total_chars = sum(os.path.getsize(p) for p in written)
    print(f"\n完成。写入 {len(written)} 个文件，总字节 {total_chars}:")
    for p in written:
        print(f"  - {p}  ({os.path.getsize(p)} bytes)")


if __name__ == "__main__":
    main()