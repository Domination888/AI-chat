# 硬件与运行边界

- 当前可验收设备是 macOS arm64、32GB 内存。不得把此设备结果宣称为 16GB 或 Windows 验收。
- 离线应用默认启动 Java API、聊天/嵌入 llama-server 和 ONNX ASR，全部回环并使用随机令牌；黍由 Java 调用本机 AstraTTS。不依赖远端 Windows 模型机器。
- 用户可以显式配置自己的 API；商业 BYOK 只替换聊天，平台提供嵌入与语音；商业客户端语音必须经商业后端。本机测试把商业后端及 ASR/AstraTTS 启动在 Mac，不改变此架构。
- 云端尚无生产域名/服务器；部署配置位于 deployment/cloud，当前不公开注册和收费。
- 当前发行版支持 Astra 与可选私人黍 Live2D，角色素材见 .local/personal/。
- 默认构建不混入个人素材、数据、模型权重或私钥。参考工程不参与发布。
