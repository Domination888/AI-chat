# PLAN-007 · TTS 性能与质量优化（v2Pro 切换 + 推理参数 + 模型放置）

> 关联：[`PLAN-003`](.joycode/plans/PLAN-003-gpt-sovits-streaming-tts.md:1)、[`tts-optimization-todo.md`](.joycode/plans/tts-optimization-todo.md:1)
> 现状文件：[`api_v2.py`](GPT-SoVITS/api_v2.py:1)、[`tts_infer.yaml`](GPT-SoVITS/GPT_SoVITS/configs/tts_infer.yaml:1)、[`启动tts.sh`](v4/启动tts.sh:1)、[`application-local.yml`](src/main/resources/application-local.yml:61)
> 目标：把"单句 4 秒音频要 50s 合成 + 音质差"压到"首句 < 15s + 后续句 < 8s + 音质可接受"。

---

## 现象与根因

### 现象
- 单句 4s 音频，首次冷启 ~50s，后续句也在数秒级
- 音质效果差（吐字含糊 / 节奏断裂 / 情感平淡）

### 根因（按影响面排序）

#### 1. v4 模型在 Mac CPU 上是性能毒药
[`tts_infer.yaml`](GPT-SoVITS/GPT_SoVITS/configs/tts_infer.yaml:48) 现在 `custom.version=v4`：
- v4 = LoRA + **BigVGAN vocoder** + Per-Layer Embedding，Conv 算子量是 v2Pro 的数倍
- BigVGAN 的 anti-aliased activation 在 CPU 上没有优化路径，纯 PyTorch fallback
- 加载时还会出现 "missing_keys 满屏" → 实际跑的是退化版
- Mac M4 MPS 对 BigVGAN 部分算子也不全，反而比 CPU 更慢

#### 2. streaming_mode=3 牺牲音质换速度
[`api_v2.py`](GPT-SoVITS/api_v2.py:386) `streaming_mode=3` 启用 `fixed_length_chunk=True`：
- 按固定长度硬切语义 token，导致词被切断（吐字含糊感主要来自这里）
- 应该用 `streaming_mode=2`（streaming + 不强制定长）

#### 3. 句级串行 + 没缓存参考音频 prompt
- 后端 `AudioController.emitTts` 每句等完整 wav 再发 SSE，没真流式
- 每次 `/tts` 请求都重算 ref_audio embedding（GPT-SoVITS 已有 cache 机制但默认未充分利用）

---

## 优化方案（按 ROI 排序）

### ★★★ 优化 1：切到 v2Pro 底模（速度 3-5 倍提升）

**原理**：黍的 LoRA 是 v4 训出的，但**权重结构与 v2Pro 兼容**（GPT-SoVITS 官方多次确认；启动脚本注释也已亲测）。v2Pro：
- 无 BigVGAN（用更轻的 vocoder）
- Conv 算子在 Mac CPU 上有 Accelerate 加速路径
- 加载快、推理快、内存占用小

**操作**：

1. 修改 [`tts_infer.yaml`](GPT-SoVITS/GPT_SoVITS/configs/tts_infer.yaml:1) 的 `custom` 段：把 `version` 从 `v4` 改成 `v2Pro`，`t2s_weights_path` / `vits_weights_path` 改成对应 v2Pro 底模路径。后端首次 TTS 时会通过 `/set_gpt_weights` & `/set_sovits_weights` 切到黍权重。

2. **第三方训好的"v2Pro 兼容版"模型放置**：
   - GPT 权重（.ckpt）→ `GPT-SoVITS/GPT_weights_v2Pro/<角色名>.ckpt`
   - SoVITS 权重（.pth）→ `GPT-SoVITS/SoVITS_weights_v2Pro/<角色名>.pth`
   - 然后在后端 `tts-profiles.<voice_id>.gpt-weights` / `sovits-weights` 字段填**绝对路径**
   - GPT-SoVITS WebUI 会自动扫描这两个目录

3. **当前黍权重保持位置不变**：`v4/黍/黍-e10.ckpt` / `v4/黍/黍_e10_s190_l32.pth`，已经在 [`application-local.yml`](src/main/resources/application-local.yml:88) 用绝对路径配好，不用挪，只是底模换成 v2Pro。

**验收**：单句 4s 音频，合成耗时从 ~50s 降到 8-15s

### ★★ 优化 2：streaming_mode 从 3 改回 2

`tts-streaming-mode: 2`（不强制定长切片，词不会断；速度比 mode=3 慢 ~10-20%，但 v2Pro 切换后总耗时仍可接受）。

### ★★ 优化 3：推理参数微调

`tts-profiles.shu` 段：`top-k: 10`（15→10，吐字稳定）、`temperature: 0.85`（1.0→0.85，少胡乱发音）、`speed-factor: 1.05`、`min-chunk-length: 20`（默认 16，长一点减少切碎）、`overlap-length: 4`（默认 2，过渡更平滑）。

### ★ 优化 4：参考音频 prompt embedding 缓存

GPT-SoVITS api_v2 有 `set_refer_audio` 接口（[`api_v2.py:519`](GPT-SoVITS/api_v2.py:519)）：后端启动时 / 首次 TTS 前调用 `GET /set_refer_audio?refer_audio_path=...`，之后 TTS 请求里仍带 `ref_audio_path` 但服务端命中缓存，省 200-500ms。

**实现**：[`VoiceServiceImpl`](src/main/java/org/example/aichat/service/impl/VoiceServiceImpl.java:1) 在 `tts-auto-switch-weights=true` 路径里追加一次 `/set_refer_audio` 调用。

### ★ 优化 5（下次再做）：真流式 chunk SSE

详见 [`tts-optimization-todo.md`](.joycode/plans/tts-optimization-todo.md:1)，本 PLAN 不展开。

---

## 模型放置目录约定（重要）

```
GPT-SoVITS/
├─ GPT_weights_v2Pro/        ← 第三方训好的角色 GPT 权重（.ckpt）放这里
├─ SoVITS_weights_v2Pro/     ← 第三方训好的角色 SoVITS 权重（.pth）放这里
├─ GPT_weights_v4/           ← v4 训的（不推荐用，慢）
├─ SoVITS_weights_v4/
└─ GPT_SoVITS/pretrained_models/
   ├─ s1v3.ckpt                       ← v2Pro 用的 GPT 底模
   └─ v2Pro/s2Gv2Pro.pth              ← v2Pro 底模 SoVITS 部分
```

**配置后端使用第三方角色权重**：
1. 把 `<角色>.ckpt` 放到 `GPT-SoVITS/GPT_weights_v2Pro/`
2. 把 `<角色>.pth` 放到 `GPT-SoVITS/SoVITS_weights_v2Pro/`
3. 在 `application-local.yml` `tts-profiles` 加 entry，填绝对路径
4. 把对应 `role_card.voice_id` 改成新的 voice_id

---

## 操作清单

### 配置层（本 PLAN 直接改）
- [x] `tts_infer.yaml` `custom` 段切到 v2Pro
- [x] `application-local.yml` `tts-streaming-mode: 2`
- [x] `application-local.yml` `tts-profiles.shu` 推理参数微调
- [x] `v4/启动tts.sh` 注释更新（v2Pro 路径生效）

### 代码层（后续做）
- [ ] `VoiceServiceImpl` 加 `/set_refer_audio` 预热
- [ ] `VoiceServiceImpl` 真流式 chunk → SSE
- [ ] 前端 MediaSource 边收边播

### 模型层（用户操作）
- [ ] （可选）下载第三方训好的 v2Pro 角色权重，放到 `GPT_weights_v2Pro/` & `SoVITS_weights_v2Pro/`
- [ ] 黍模型保持原位（`v4/黍/`），只是底模切到 v2Pro

---

## 验收
- [ ] 首句 TTS 合成 < 15s（v2Pro 加载 + 推理）
- [ ] 后续句 < 8s（无加载，纯推理）
- [ ] 吐字清晰、无明显跳音
- [ ] Mac 内存占用 < 4GB（v2Pro 比 v4 省 ~1.5GB）

---

## 风险与回退
1. **v2Pro 加载黍的 v4 权重出现 missing_keys 大面积报错** → 启动脚本注释已亲测过 v2Pro 兼容；最坏情况回退 v4，只改 `streaming_mode=2 + 调参`
2. **第三方下载的模型版本不匹配 v2Pro** → 看权重大小：v2Pro 的 GPT 权重 ~150MB，SoVITS ~70MB；偏离太多说明是 v3/v4，需要找 v2Pro 版本
3. **参考音频时长太长导致 prompt 编码慢** → 控制 ref-audio-path 的 wav 在 5-10 秒以内