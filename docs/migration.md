# 数据迁移与恢复

## 当前数据位置

macOS 离线数据目录为 `~/Library/Application Support/story-space-offline/data/`。Windows 使用 Electron appData 下的同名应用目录。核心文件是 stories.sqlite、attachments、models、`.credentials-key`、日志，以及 migration-report.json 和 migration-review。

本机已把原 MySQL user_id 0 与 1 的 24 个篇章合并到已有离线数据。当前迁移结果为 **26 个篇章、120 条消息、11 个角色、26 个世界、64 个回合、11 条语音记录**。原库有一条消息关联两个 RP 快照，快照均保留，历史 API 按最新快照投影而不重复正文。可关联旧消息 116 条、已有离线消息 4 条；另 1 条失去篇章归属的旧消息保存在审阅副本。旧库有 23 个空篇章，原文集中在 1 个篇章中；迁移保留这些归属，不推测和重分配历史。原 MySQL 未写入。

私人备份目录：`~/.codex/backups/ai-chat/migration-20261013-232925/`。其中 offline-retired 是切换前完整数据，offline-before 是迁移前 SQLite 一致性副本，mysql-export/offline-export/merged-export/verified-export 是导出与核验材料，migration-report.json 记录计数和目标。目录不参与源码导出或打包。

以上数量与私人备份路径是已有迁移记录，并非本次重新核验的实时统计。日常源码开发使用 `.local/dev/offline/data/`，不自动读取或改写上述安装版数据。

## 数据语义

- 角色、对话正文、消息时间、世界、收藏和已有回合快照按原文保存。
- 同一已知世界关系保留，缺失 RP 结构的篇章补建世界和原文回合。
- 两份数据的数字 ID 独立映射；标识冲突时创建确定的新标识。示例库角色仅在完整内容相同时复用。
- 当前设备已有模型配置、加密密钥和设置保持在设备上；原 MySQL 密码/API key 不导入。
- MemOS、Redis 原始索引、未交付记忆候选、主动研究状态与个人角色资料保存于 migration-review；未确认世界归属的事实不自动注入。
- 向量重新生成；本机 63 条待办索引均已完成。以后导入的数据由应用后台处理。缓存向量不是对话原始数据。
- 角色的旧 Live2D、第三方音色和私有素材不会进入通用发行包；显式个人构建可带原素材，完整角色卡/lore 已有独立导入与保存路径。

## 可复用流程

1. 关闭空闲应用，确认无生成和写入；建立私人工作区/数据备份。
2. 在 MySQL 只读一致性事务内导出所选账号。个人库需要保留未用角色时加 --all-roles；不导出认证密钥。
3. 用 SQLite backup API 获取当前本地一致性副本；导出关系与原始附件。需要时读取 MemOS 与 Redis 的审阅副本。
4. 通过 merge-exports.py 建立所有编号映射；只向全新隔离数据库的 `/api/v1/offline/import` 导入。
5. 核验 integrity_check、表计数、每条消息的正文/发送者/篇章、世界和回合引用、收藏及附件；验证 HTTP 再导出。
6. 停止验收服务，把旧数据目录移至完整备份，再将已验证目录原子切换为应用 data；异常时恢复原目录。
7. 启动实际桌面，检查角色/篇章/历史和后台索引，不通过写测试消息改变真实故事。

导出与合并命令示例：

```bash
python scripts/release/export-legacy.py <私人备份>/mysql.json --user-id 0 1 --all-roles
python scripts/release/merge-exports.py <私人备份>/merged.json <私人备份>/offline.json <私人备份>/mysql.json
```

MySQL 连接通过 LEGACY_MYSQL_* 环境变量提供；不要把真实密码写到命令参数或文档。导出工具需 pymysql，可在独立构建 venv 安装。大于 128MiB 的可移植导出需要离线工具处理，不能绕过 API 的大小限制。

## 回滚

关闭应用，**先另外备份切换后的数据**，再将当前 data 移至新备份位置，把 offline-retired 复制/移动回应用 data。保留 `.credentials-key` 与其匹配的 model_connection；缺少原密钥会无法解密已保存连接。模型目录很大，可对只读模型使用同卷硬链接，但 SQLite 和凭据必须是独立文件。

云恢复分别保护 PostgreSQL、S3 对象和加密/签名密钥；只恢复 SQL 不能恢复附件，只有对象也不能恢复世界和权限。正式云迁移不通过本机匿名身份直接创建已验证的邮箱账号。

## 陪伴功能与旧喜好恢复

新版通过 V2 增量迁移增加 `companion_setting`、`companion_interest`、`companion_daily`，保留原 V1 校验和。定位、喜好管理和 AI 日报入口位于侧栏“陪伴设置”和“AI 日报”。喜好和环境只注入现实陪伴模式；日报支持提问资料与角色主动开场投递。设备自动定位、外部城市/天气查询、日报联网读取和定时提醒均有独立开关。

2026-10-25 已从安装版 `migration-review/legacy-auxiliary-review.json` 恢复原 user_id 0、1 的 **12 条兴趣**到本地账号，保留原启用状态。恢复后核验为 **26 个篇章、120 条消息、12 条兴趣**，22 张原有表的所有行与恢复前一致，SQLite integrity_check 通过。恢复前一致性备份及报告位于 `~/.codex/backups/ai-chat/companion-restore-20261025-221709/`。此记录只说明本机恢复，不表示所有旧功能已迁移。

恢复工具默认只读预检，应用修改必须显式提供全新的私人备份路径；只导入选定账号的喜好，不覆盖目标中的同名主题，不启用网络，不改写故事：

```bash
python3 scripts/release/restore-companion.py \
  --database <离线数据目录>/stories.sqlite \
  --review <离线数据目录>/migration-review/legacy-auxiliary-review.json \
  --user-id 0 1
# 确认预检结果后，在同一命令后加 --apply --backup <新私人目录>/stories-before.sqlite
```

旧版喜好恢复属于一次性维护操作，发行产品不提供单独的旧喜好导入入口；需要补迁时使用上述维护脚本。新的故事导出与合并会保留喜好及陪伴设置。整库导入会保留城市、时区和喜好，但关闭自动定位及联网任务，供新设备重新选择。

## 一次性配置补迁（V3）

2026-10-26 已将本机旧配置的 4 个技能与内置 Prime 工具补迁到安装版离线库。核验结果为 26 个篇章、120 条消息、11 个角色、12 条喜好、4 个技能、1 个工具，`integrity_check=ok`。故事及原有非配置表逐表指纹保持一致；网络读取仍关闭，工具未自动授予模型调用权限。迁移前一致性备份和隔离验证副本位于 `~/.codex/backups/ai-chat/config-migration-20261026-185648/`。这是一条本机迁移记录，不代表商业账号的数据迁移。

旧配置迁移不是上线功能。发行界面、桌面桥接与后端专用迁移接口均已移除；正常故事导出、空库备份恢复、技能/MCP/喜好增删改继续保留。维护工具位于 `scripts/release/`，不随桌面运行时装载：

1. 用 SQLite backup API 建立私人一致性备份及逐表指纹，并复制至隔离暂存目录；保留与连接配置匹配的密钥。
2. `node scripts/release/migrate-capabilities.cjs --config config` 默认只读列出技能和工具。准备隔离离线服务后，通过正常技能/MCP 接口补迁；`--apply` 必须提供本机服务地址、`--verified-backup` 和新的私人 `--report` 路径，令牌只从 `STORY_MIGRATION_TOKEN` 环境变量读取。已有同名条目保留；只有确认两个内置技能尚未持久化时才加 `--include-builtin-defaults`。
3. 核验技能正文、工具解密与实际调用后停止隔离服务。`apply-staged-capabilities.py` 默认只读预检，接收 `--database`、`--staged`、`--baseline`、`--backup`；显式 `--apply` 才以事务补入 V3 配置。它拒绝基线改变或已升级目标，不替换数据库文件、不覆盖已有故事；此工具仅用于已核验的 V2→V3 一次补迁。
4. 重新核验原表指纹、配置数量及数据库完整性。保留备份和报告；后续发行版不再暴露旧配置导入能力。

个人黍构建运行 `python3 scripts/release/stage-shu.py`，随后以 STORY_PERSONAL_SHU=1 打包。模型、动作、表情、Core、完整角色卡和 lore 从 `.local/personal/` 复制；不复制故事或任意 MCP 密钥。桌面启动会把完整角色资料导入当前账号并优先显示黍。

导出/合并/离线空库导入现在覆盖 character_profile、message_context、memory_feedback、companion_skill 和 proactive_candidate。重映射候选 ID 时同步改写消息引用；恢复的未投递候选归档，技能定时任务和联网/定位开关关闭，原文与用户纠正分别保留，向量重新生成。MCP 凭据须在目标设备重新配置。
