# Agent 开发指引

幕间 Story Space 是角色扮演与连续故事桌面应用。版本术语和源码边界见 [开发与文档规范](docs/项目规范.md)。

- 开发与打包命令见 [README](README.md#开发)，发行依赖和运行条件以 [打包说明](docs/packaging.md) 为准。Live2D 是可选功能。
- 开发数据默认在 `.local/dev/` 与 `story-space-dev` 专用 Docker volumes 中，与安装版数据隔离。显式 SQLite 映射的用法见打包说明。
- 私人角色素材放在 `.local/personal/`；清理时保留运行数据、模型和私人配置。
- 数据迁移遵循 [迁移与恢复](docs/migration.md)，测试使用临时目录与专用测试库。用户授权的数据迁移按会话范围执行。
- [架构](docs/architecture.md)、[实现](docs/implementation.md)、[脚本](docs/scripts.md)、[排障](docs/troubleshooting.md) 和 [待办](docs/TODO.md) 分别维护对应内容。`.cursor/memory/MEMORY.md` 和硬件规则只记录长期事实。
- 提交被文件大小钩子拒绝时，按报错拆分文件，重新暂存并提交。
- 写新函数或规则前，先搜索项目中的现有实现。
