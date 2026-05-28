# PLAN-009: Live2D 表情控制 + 部署集成

## 任务概述
借鉴 my-neuro 项目方案，在现有 Spring Boot + Vue3 架构中集成 pixi-live2d-display，
让 LLM 输出中的中文情绪标签（`<开心>` `<生气>` 等）驱动黍模型的表情和动作，
同时实现 TTS 音频驱动的口型同步。

## 黍模型可用资源清单
**Expressions（4个）**：闭眼、皱眉、闭一只眼、震惊
**Motions（Tap#4 组，9个）**：种到土里、种到地里、谷种入田野、钓鱼、掐腰、有大麟、生气、晃手、闭眼
**内置能力**：EyeBlink（自动眨眼）、LipSync（口型，Gain=5.0）、MouseTracking、AutoBreath

## 情绪标签设计（6种，对齐 my-neuro）
`<开心>` `<生气>` `<难过>` `<惊讶>` `<害羞>` `<俏皮>`

---

## TODO: 前端 — Live2D 渲染层搭建

- [ ] 安装依赖：`pixi.js` + `pixi-live2d-display`（Cubism 4 版本）
- [ ] 下载 Live2D Cubism 4 Core SDK（`live2dcubismcore.min.js`），放到 `client/src/public/live2d-core/`
- [ ] 将 `黍黍模型-by什行在要/` 整个目录复制到 `client/src/public/live2d/shu/`，修正 `model3.json` 中 `\r` 换行符
- [ ] 创建 `Live2DCanvas.vue` 组件：用 `<canvas>` + PIXI.Application 初始化 pixi-live2d-display
- [ ] 在 `Live2DCanvas.vue` 中加载黍模型，配置自动眨眼、鼠标追踪、idle 动作
- [ ] 将 `Live2DCanvas.vue` 挂载到 `App.vue` 主界面右侧/右下角，z-index 置顶

## TODO: 前端 — 情绪映射配置

- [ ] 创建 `client/src/src/live2d/emotion-mappings.js`，定义情绪→表情/动作映射：
  ```
  开心 → expression:无(默认), motion:晃手
  生气 → expression:皱眉, motion:生气
  难过 → expression:闭眼, motion:掐腰(待机)
  惊讶 → expression:震惊, motion:有大麟
  害羞 → expression:闭一只眼, motion:钓鱼
  俏皮 → expression:闭一只眼, motion:谷种入田野
  ```
- [ ] 映射表导出为常量，供 emotion-parser 和 Live2DCanvas 使用

## TODO: 前端 — 情绪标签解析器

- [ ] 创建 `client/src/src/live2d/emotion-parser.js`
- [ ] 实现 `parseEmotionTags(text)`：正则 `/<([^>]+)>/g` 提取标签，返回 `{emotion, position, fullTag}[]`
- [ ] 实现 `stripEmotionTags(text)`：移除所有 `<xxx>` 标签，返回纯文本
- [ ] 实现 `prepareTextWithMarkers(text)`：剥离标签 + 记录每个标签在纯文本中的位置和对应映射

## TODO: 前端 — Live2D 控制器（核心驱动）

- [ ] 创建 `client/src/src/live2d/live2d-controller.js`
- [ ] 实现 `triggerExpression(emotion)`：根据映射表调用 `model.expression(name)`
- [ ] 实现 `triggerMotion(emotion)`：根据映射表调用 `model.motion(group, index)`
- [ ] 实现 `triggerEmotion(emotion)`：同时触发表情+动作
- [ ] 实现 `startLipSync(audioSource)`：用 WebAudio Analyser 实时分析频率，驱动 `model.internalModel.coreModel.setParameterValueById('ParamMouthOpenY', value)`
- [ ] 实现 `stopLipSync()`：停止口型驱动，恢复默认

## TODO: 后端 — System Prompt 注入情绪标签协议

- [ ] 在 `role_system.txt` 末尾追加情绪标签使用说明：
  ```
  【情绪标签】
  你可以在回复中自然地嵌入以下情绪标签来控制表情和动作：
  <开心> <生气> <难过> <惊讶> <害羞> <俏皮>
  想用就用，不想用就不用。标签会自动被处理，不会出现在用户看到的文字中。
  ```
- [ ] 在 `system.txt` 中也追加同样的说明（作为基底规范兜底）

## TODO: 后端 — SSE 流增加 emotion 事件

- [ ] 在 `ChatController.java` 的 SSE 事件协议中新增 `emotion` 事件类型：
  `event: emotion  data: {"emotion":"开心","position":12}`
- [ ] 在 `ChatServiceImpl.chatStream()` 的流式 token 拼接过程中，检测到 `<xxx>` 标签时：
  1. 发送 `emotion` SSE 事件给前端
  2. 从文本流中剥离标签（不发给前端 `text` 事件的 delta）
- [ ] 注意：标签剥离在**后端**完成，前端收到的 `text` delta 已经是纯文本

## TODO: 前端 — SSE 事件处理集成

- [ ] 在 `App.vue` 的 SSE 处理逻辑中，新增 `emotion` 事件监听
- [ ] 收到 `emotion` 事件时，调用 `live2d-controller.triggerEmotion(emotion)`
- [ ] 收到 `tts` 事件（音频 chunk）时，启动/维持 `live2d-controller.startLipSync()`
- [ ] 收到 `done` 事件时，延迟 2s 后调用 `live2d-controller.stopLipSync()` 并恢复默认表情

## TODO: 前端 — 口型同步与 TTS 音频联动

- [ ] 在 `App.vue` 的音频播放逻辑中，将 Audio 元素连接到 Live2D 控制器的 Analyser
- [ ] 利用黍模型内置的 LipSync 控制器（Gain=5.0），优先尝试用 pixi-live2d-display 内置口型
- [ ] 如果内置 LipSync 不生效，回退到手动 `ParamMouthOpenY` 驱动方案

## TODO: 验证与调优

- [ ] 端到端测试：用户发消息 → LLM 回复含 `<开心>` → 前端触发晃手动作 + 默认表情
- [ ] 验证标签剥离：用户看到的文字不含 `<开心>` 标签
- [ ] 验证口型同步：TTS 播放时黍的嘴巴随音频开合
- [ ] 验证表情恢复：对话结束后 2s 恢复默认表情
- [ ] 调优：如果 Gemma4 对标签遵守率低，在 System Prompt 中增加 few-shot 示例

## 文件变更预估

| 文件 | 变更类型 | 说明 |
|---|---|---|
| `client/src/package.json` | 修改 | 添加 pixi.js + pixi-live2d-display 依赖 |
| `client/src/public/live2d-core/` | 新增 | Cubism 4 Core SDK |
| `client/src/public/live2d/shu/` | 新增 | 黍模型资源 |
| `client/src/src/live2d/` | 新增 | 4个文件：Live2DCanvas.vue, emotion-mappings.js, emotion-parser.js, live2d-controller.js |
| `client/src/src/App.vue` | 修改 | 挂载 Live2DCanvas + SSE emotion 事件处理 |
| `backend/.../prompts/system.txt` | 修改 | 追加情绪标签协议 |
| `backend/.../prompts/role_system.txt` | 修改 | 追加情绪标签协议 |
| `backend/.../controller/ChatController.java` | 修改 | 新增 emotion SSE 事件 |
| `backend/.../service/impl/ChatServiceImpl.java` | 修改 | 流式输出中提取+剥离情绪标签 |