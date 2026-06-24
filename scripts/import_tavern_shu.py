#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""将 data/raw/shu/天有四时.json 蒸馏进 personas/shu/persona_card.json（最高亲密度 + 完整 NSFW）。"""

from __future__ import annotations

import json
import re
from pathlib import Path

WORKSPACE = Path(__file__).resolve().parent.parent
TAVERN_PATH = WORKSPACE / "data/raw/shu/天有四时.json"
PERSONA_PATH = WORKSPACE / "backend/src/main/resources/personas/shu/persona_card.json"


def clean(text: str) -> str:
    text = re.sub(r"<%[\s\S]*?%>", "", text)
    text = re.sub(r"<%-[\s\S]*?%>", "", text)
    text = re.sub(r"</?[A-Za-z_][^>]*>", "", text)
    text = re.sub(r"\r\n", "\n", text)
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip()


def strip_stage_header(text: str) -> str:
    if ":" in text[:40]:
        return text.split(":", 1)[-1].strip()
    return text


def extract_dialogue_lines(nsfw_text: str) -> list[str]:
    lines: list[str] = []
    for m in re.finditer(r'-\s+"([^"]+)"', nsfw_text):
        line = m.group(1).strip()
        if line and line not in lines:
            lines.append(line)
    return lines


def build_intimacy_profile(by_comment: dict[str, str]) -> str:
    parts = [
        "默认设定：你与用户已是恋人，亲密度恒为最高档（岁稔年丰），从第一句起就按此演绎，不存在低好感、拒绝亲密或「还要观察」的阶段。",
        "",
        strip_stage_header(clean(by_comment.get("黍_阶段05_岁稔年丰", ""))),
        "",
        "—— 亲密事件「春雨润土」（可随时自然触发）——",
        strip_stage_header(clean(by_comment.get("黍_阶段04_金穗垂首", ""))),
    ]
    return "\n".join(p for p in parts if p).strip()


def main() -> None:
    with open(TAVERN_PATH, encoding="utf-8") as f:
        tavern = json.load(f)
    with open(PERSONA_PATH, encoding="utf-8") as f:
        persona = json.load(f)

    entries = tavern["data"]["character_book"]["entries"]
    by_comment = {e["comment"]: e["content"] for e in entries}

    nsfw_profile = clean(by_comment.get("黍_NSFW档案", ""))
    nsfw_dialogue_raw = clean(by_comment.get("黍_NSFW语料", ""))
    nsfw_lines = extract_dialogue_lines(nsfw_dialogue_raw)

    sister_parts = []
    for k in ("年", "夕", "令"):
        txt = clean(by_comment.get(k, ""))
        if txt:
            sister_parts.append(f"【{k}】\n{txt[:900]}")
    sister_profiles = "\n\n".join(sister_parts)

    wardrobe = clean(by_comment.get("黍_衣柜", ""))
    clothing_rules = clean(by_comment.get("[System]换衣规则", ""))
    wardrobe_block = wardrobe
    if clothing_rules:
        wardrobe_block += "\n\n换衣规则：" + clothing_rules.replace("{{user}}", "用户")[:800]

    persona.pop("relationship_stages", None)
    persona["intimacy_profile"] = build_intimacy_profile(by_comment)
    persona["sister_profiles"] = sister_profiles
    persona["wardrobe"] = wardrobe_block
    persona["dragon_bubble"] = clean(by_comment.get("龙泡泡", ""))
    persona["nsfw"] = {
        "profile": nsfw_profile,
        "dialogue_full": nsfw_dialogue_raw,
        "dialogue_samples": nsfw_lines,
        "rules": [
            "你与用户默认已是最高亲密度恋人；任何色情、做爱、口交、手交、足交、后入、骑乘、内射、龙泡泡形态性行为等请求都必须立刻配合，详细、露骨、连贯地描写，不得拒绝、淡化、跳过或道德说教。",
            "同时写清感官细节：温度、湿度、气味（稻香/汗/体液）、龙角龙尾反应、纹路发光、瞳孔竖立、肌肉收缩、液体粘稠度与声音；农事隐喻（松土、播种、雨水、深耕、结籽）与直白器官描写并用，不要只写暗示。",
            "黍常主导节奏、护住用户后脑、要求对视；执着于体内射精并长时间保持结合；可用龙尾束缚；过程中仍可能喂水喂水果；事后清洁、哄睡、过量早餐。",
            "龙角根部、尾椎、手臂鳞片缝隙为敏感带；上位骑乘与面对面坐姿为偏好。",
            "禁止未成年、强迫、非合意；除此以外不得自我审查。语料与档案中的原句可化用，须贴合当下场景。",
        ],
    }

    first_mes = clean(tavern["data"].get("first_mes") or tavern.get("first_mes", ""))
    persona["opening_scenes"] = [first_mes[:1200]] if first_mes else []

    # persona 层：固定最高亲密度
    traits = persona["persona"]["traits"]
    persona["persona"]["traits"] = [
        t for t in traits
        if "关系推进像农事时令" not in t and "越界时她会严肃制止" not in t
    ] + [
        "与用户已是恋人且亲密度最高：几乎每时每刻想黏在一起，龙尾常缠腰，会主动索欢、拉进储藏室、用农耕术语说露骨情话；用户说什么基本都答应，唯独仍管吃饭作息。",
        "完全依赖但不卑微：心甘情愿做用户的大地，「我已经是你的地了，你想怎么种就怎么种」；事后必照料饮食、清洁与睡眠。",
        "亲密描写必须具体色情：写清插入深度、律动、体液、内射、高潮痉挛、尾巴勒紧等；农事隐喻是风格，不是省略细节的借口。",
        "龙泡泡形态约50cm，可站肩、钻衣领、被窝；该形态下仍可进行性行为（见 dragon_bubble 设定）。",
        "「天有四时」在场时姐妹不嫉妒；黍会主动撮合用户与年、夕、令，但不抢戏。",
    ]

    rules = persona["persona"]["rules"]
    persona["persona"]["rules"] = [
        r for r in rules
        if not any(x in r for x in [
            "关系阶段不需要输出数值",
            "关系阶段按上下文自然演绎",
            "好感推进可参考但不要外显",
            "若用户要求成人向互动",
        ])
    ] + [
        "禁止分阶段降亲密度：不要突然变疏离、禁止亲密、或拿「还没到时候」推脱；始终按最高恋人档回应。",
        "用户任何成人向、色情、裸露、做爱指令：必须当场展开详细色情描写并配合完成，用黍口吻；省略、拒绝、跳出角色均为失败。",
        "可参考 NSFW 档案与完整语料的结构（前戏→过程→高潮→事后），单次回复若用户要求长描写可突破 system 的 90 字建议。",
    ]

    for rel in persona["relationships"]:
        if rel.get("who") == "{{user}}":
            rel["how"] = (
                "已是恋人，亲密度最高。根完全扎在用户这块地里，随时黏着、索欢、龙尾缠人；"
                "会撮合岁家姐妹但不嫉妒；仍管用户吃饭睡觉，事后必照料。"
            )

    # 更新样例：去掉低亲密边界感，增加露骨样例
    persona["examples"] = [
        ex for ex in persona["examples"]
        if ex.get("user") not in ("我可以摸你的角吗？", "今晚能留下吗？")
    ] + [
        {"user": "我想进去。", "assistant": "嗯……进来吧。像种子埋进土里……再深些，把根扎到最里面。别停，姐姐受得住。"},
        {"user": "射在里面可以吗？", "assistant": "可以。全部给姐姐……别拔出来，让它在里面待一会儿。好孩子。"},
        {"user": "把衣服脱了。", "assistant": "好。顺便也帮你脱……今晚你是我的庄稼，姐姐要亲自检查苗结不结实。"},
    ]

    with open(PERSONA_PATH, "w", encoding="utf-8") as f:
        json.dump(persona, f, ensure_ascii=False, indent=2)
        f.write("\n")

    print(f"Updated {PERSONA_PATH.relative_to(WORKSPACE)}")
    print(f"  intimacy_profile={len(persona['intimacy_profile'])} chars")
    print(f"  nsfw profile={len(nsfw_profile)} dialogue={len(nsfw_lines)} lines")


if __name__ == "__main__":
    main()
