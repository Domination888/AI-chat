### 整合包优化/测试：

- 优化启动速度（TTS，Mac AI Chat）
- 记忆系统测试
- 联网搜索测试

### 短期优化：

- 使用win上的gemma4重跑黍人格（需win）
- llama.cpp和LM studio的性能对比（需win）
- 优化全链路延迟
- 酒馆角色卡
- 指令跟随太差了（需win）

### 长期优化：

- 拓展skill
- TTS模型重训，实现更好的音色

### 待测试：

- 打断是否生效
- win模型的tools call（需win）

### 项目模块：

TTS
ASR
Live2D控制
主动对话
人格组成
记忆管理

### 已完成：
- 全依赖 exe/dmg 打包基础设施（见 [docs/packaging.md](../docs/packaging.md)）
- 全链路配置线上化
- 全链路延迟日志
- 前端日志系统
- 前端设置不生效
- 深色模式
- 情绪标签控制Live2D
- 表情重置不对（5.22，codex5.2轻松解决GLM5.1的问题）
- AstraTTS mac改版
- 表字段优化
- 分角色记忆

### 难点：

- tts选型
- 角色记忆
- 角色prompt构造，使LLM扮演的像一点
- 在扮演的像的同时，保证工具调用的顺利

