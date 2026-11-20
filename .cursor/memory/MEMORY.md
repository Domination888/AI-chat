# 当前项目事实

- 项目为幕间 Story Space，双版本：托管商业版与开源离线版。
- 主源码是 apps/desktop、apps/server、packages/rp；参考源码是 external。
- 默认入口 npm run setup/build/dev/test；桌面只有一套 npm 依赖锁，Java wrapper 在 tools/maven。
- 商业版 PostgreSQL/pgvector + S3，邀请码/邮箱验证、MFA 运营、加密 BYOK；尚无支付。
- 离线版 SQLite/FTS5，本地 llama/ONNX 语音，签名权重单独导入，默认不联网。
- macOS 数据目录 ~/Library/Application Support/story-space-offline/data，不在仓库；迁移备份见 docs/migration.md。
- 原 MySQL 与已有离线篇章已合并，本地代码通过 API/用户身份控制数据归属。
- 未完成工作只看 docs/TODO.md；架构和实现分别看 architecture/implementation，踩坑看 troubleshooting。
- 私人角色素材保存在 .local/personal/。
