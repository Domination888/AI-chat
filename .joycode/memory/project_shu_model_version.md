---
name: 黍模型用 GPT-SoVITS v4 训练但需要按 v2Pro 推理
description: 黍 LoRA 权重虽然是 v4 版本训练产出，但推理时 tts_infer.yaml 必须用 v2Pro 加载，且 webui 也是用 v2Pro 推理
type: project
---

黍的 GPT 权重 (`v4/黍/黍-e10.ckpt`) 和 SoVITS 权重 (`v4/黍/黍_e10_s190_l32.pth`) 是用 GPT-SoVITS v4 训练流程训出来的，但 **推理时必须用 v2Pro 架构加载**，不能用 v4。

**Why:**
- GPT-SoVITS 的训练/推理权重在 v2、v2Pro、v4 之间是通用的（这个项目的设计），权重结构兼容
- v2Pro 是当前最新版本，效果和速度都最好；v4 推理路径在 Mac CPU 上又慢又有 BigVGAN 问题
- 用户在 webui 推理时显式选的就是 **v2Pro**（截图佐证：选项卡 "训练模型的版本: v2Pro"，权重列在 `GPT_weights_v2Pro/` 和 `SoVITS_weights_v2Pro/` 下）
- webui 用 v2Pro 加载这套权重生成的音色又快又像 → 证明 v2Pro 是正确推理架构

**How to apply:**
- `GPT-SoVITS/GPT_SoVITS/configs/tts_infer.yaml` 的 `custom` 块必须 `version: v2Pro`，**不要因为权重文件路径放在 `v4/` 目录下就误改回 v4**
- 启动日志里出现的 `missing_keys=['cfm.base_model.model.estimator...']` 是 **v3/v4 推理时残留的 cfm 估计器骨架在 v2Pro 权重里没有对应键 → 实际不会被走到**，属于库的兼容警告，不影响 v2Pro 推理（我之前误把这条当 "version 错配" 的证据，是误判）
- 后续若用户提供其它角色的权重，默认按 v2Pro 加载即可（除非角色明确给的是 v3 路径下的权重）
- 训练目录命名 `v4/<角色>/` 只是项目历史命名习惯，不代表推理版本