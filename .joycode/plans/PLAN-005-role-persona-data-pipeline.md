# PLAN-005 · 角色人格塑造数据处理流水线（以"黍"为样板）

> 上层目标：[`PLAN-000`](.joycode/plans/PLAN-000-product-overview-neuro-like-ai.md:1)
> 上游依赖：[`PLAN-001`](.joycode/plans/PLAN-001-ai-chat-role-play-refactor.md:1) 阶段2/阶段5
> 硬件约束：[`.joycode/rules/00-hardware-and-deployment.md`](.joycode/rules/00-hardware-and-deployment.md:1)
> 复用模块：[`RagServiceImpl`](src/main/java/org/example/aichat/service/impl/RagServiceImpl.java:1)、[`role_system.txt`](src/main/resources/prompts/role_system.txt:1)、[`RoleCard`](src/main/java/org/example/aichat/dto/RoleCard.java:1)

## Task Summary

把 PRTS 维基（如 https://prts.wiki/w/%E9%BB%8D ）里成百上千条台词/剧情/档案，**不塞进 prompt**，而是用 LLM 做一次**离线预处理**，拆成三层："人设卡"（进 system prompt，固定 ≤800 字）+ "记忆卡片"（短句 FAQ/口癖/关系，进 RAG）+ "原文语料块"（进 RAG，用于剧情细节追问）。最终让模型既有"稳定人格"又有"海量事实可检索"。

## 三层数据架构

```
原始资料(wiki HTML/文本) 
    │
    ▼  [抓取+清洗]
raw/黍/*.md  (档案/语音/剧情/干员对话/模组/生日…分文件)
    │
    ├──▶ [LLM 压缩] ──▶ persona_card.json ──▶ 注入 system prompt（固定不变）
    │                    (身份/性格/口癖/禁忌/输出风格) ≤ 800 字
    │
    ├──▶ [LLM 抽取]  ──▶ memory_cards.jsonl ──▶ RAG 细粒度库
    │                    (每条 1~3 句：观点/关系/事件/金句) 
    │
    └──▶ [滑窗切块]  ──▶ raw_chunks.jsonl  ──▶ RAG 粗粒度库
                         (500 字/块，用于"原文级"追问兜底)
```

**检索时**：query → 同时查 memory_cards（权重高）和 raw_chunks（权重低）→ 拼进 prompt 前注入。

---

## TODO: 阶段 1 · 原始资料采集与清洗
- [ ] 新建目录 `data/raw/shu/`（shu = 黍）
- [ ] 写脚本 `scripts/fetch_prts.py`：给定词条名（黍），拉 `https://prts.wiki/api.php?action=parse&page=...&format=json`，拿 wikitext
- [ ] 按章节切分并输出：`档案.md / 语音.md / 模组.md / 干员对话.md / 剧情出场.md / 基建对话.md / 生日语音.md`
- [ ] 去模板/去表格噪声（`{{...}}`、`[[文件:...]]`、`<ref>`），保留纯文本
- [ ] **验收**：7 个分类 md 文件，总字数合理（黍预期 1.5~3 万字）

## TODO: 阶段 2 · 人设卡压缩（persona_card）
- [ ] 设计压缩 prompt（`scripts/prompts/build_persona.md`），要求 LLM 输出定长 JSON：
  ```
  { name, aka[], identity, appearance, background_oneliner,
    personality[5], speech_style, catchphrases[], taboo[],
    relationships[{who, how}], output_rules }
  ```
- [ ] 调 Win LM Studio `:1234/v1/chat/completions`（Gemma3-27B），输入合并后的原始 md，`response_format=json_object`，`temperature=0.2`
- [ ] 产出 `data/processed/shu/persona_card.json`，**硬性上限 ≤ 800 中文字**；超了就再压一轮
- [ ] 人工校对一次（只看一次，改完锁版本）
- [ ] **验收**：把 json 渲染回 [`role_system.txt`](src/main/resources/prompts/role_system.txt:1) 的 `{{profile/background/personality/exampleDialogue}}` 占位符，system prompt 总长 ≤ 1200 tokens

## TODO: 阶段 3 · 记忆卡片抽取（memory_cards）
- [ ] 设计抽取 prompt（`scripts/prompts/extract_memory.md`），对每个分类 md 分别跑：
  - 输入：一段原文（≤2000 字）
  - 输出：JSONL，每行 `{type, title, content, keywords[]}`
  - type ∈ { 自述, 口癖, 关系, 事件, 观点, 金句, 战斗风格, 日常喜好 }
  - content ≤ 80 字，**第一人称优先**
- [ ] 滑窗跑完所有 md，合并去重（按 title+content hash）
- [ ] 产出 `data/processed/shu/memory_cards.jsonl`，预期 100~300 条
- [ ] **验收**：随机抽 20 条人读，≥90% 表述准确、第一人称一致

## TODO: 阶段 4 · 原文块兜底库（raw_chunks）
- [ ] 复用 [`RagServiceImpl.reload()`](src/main/java/org/example/aichat/service/impl/RagServiceImpl.java:180) 的 500/80 滑窗策略
- [ ] 把阶段1的 7 个 md 直接塞进 `src/main/resources/rag/shu/`
- [ ] 文件命名 `shu_<category>.md`，方便 source 溯源
- [ ] **验收**：`POST /api/rag/reload` 后 chunks 数 > 0，能检索到剧情原句

## TODO: 阶段 5 · 后端接入（按角色隔离 RAG）
- [ ] [`RoleCard`](src/main/java/org/example/aichat/dto/RoleCard.java:1) 增加字段：`roleCode`（如 "shu"）、`personaCardPath`
- [ ] 改造 [`RagServiceImpl`](src/main/java/org/example/aichat/service/impl/RagServiceImpl.java:1)：
  - 加载路径 `classpath*:rag/{roleCode}/*.md` + `classpath*:rag/{roleCode}/memory_cards.jsonl`
  - Redis key 改为 `rag:chunks:{roleCode}`
  - `retrieveContext(roleCode, query, topK)` 按角色隔离
  - memory_cards 的权重系数 × 1.3（优先于原文块）
- [ ] [`PromptServiceImpl`](src/main/java/org/example/aichat/service/impl/PromptServiceImpl.java:1)：启动时把 `persona_card.json` 渲染进 system prompt 缓存
- [ ] **验收**：切到"黍"角色问"你喜欢钓鱼吗"→ 命中 memory_cards 的钓鱼相关条目，回复符合人设

## TODO: 阶段 6 · 质量回归
- [ ] 准备 20 条测试 query（身份/口癖/关系/剧情/开放闲聊 各 4 条）
- [ ] 人工打分：人设一致性 / 事实正确性 / 语气自然度（1-5 分）
- [ ] 平均 ≥ 4.0 通过；不过 → 回阶段 2/3 调 prompt 重跑
- [ ] **验收**：生成 `data/processed/shu/eval_report.md`

---

## 决策锁定（2026-05-09）

1. ✅ **采集**：MediaWiki API 拉 wikitext（`action=parse&prop=wikitext`）
2. ✅ **压缩**：不跑本地 LLM，直接由 Claude（当前 AI）在对话中读取 raw 文件 → 手工产出 `persona_card.json` 和 `memory_cards.jsonl`，锁版本入库
3. ✅ **memory_cards**：不分场景，一张总表（字段仅 `type / content / keywords`）
4. ✅ **角色范围**：只跑"黍"一个，脚本参数化但不做批量生成

## 风险

- prts.wiki 对爬虫不友好时 → 手动复制粘贴 7 个章节也能跑，不阻塞
- 本地 LLM 压缩质量不稳 → 允许人工编辑 `persona_card.json`，视为"版本锁定的金标准"
- RAG 召回漂移 → memory_cards 命中后，原文块只取 top1 作补充，避免上下文过长