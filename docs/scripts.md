# 脚本说明

本项目脚本按使用频率分三层：日常开发只使用一键启动和停止；构建打包走 `scripts/package-all.sh`；语料和打包模板脚本只在维护对应流水线时使用。

## 日常入口

| 脚本 | 用途 | 备注 |
| --- | --- | --- |
| `startup-scripts/start-all.sh` | 启动完整开发环境 | 默认入口。拉起 SearXNG、后端、前端、Electron、ASR，并检查远端 Astra TTS。 |
| `startup-scripts/stop-all.sh` | 停止完整开发环境 | 根据 `unified-logs/pids/pids.txt` 和端口兜底清理本机进程。 |
| `scripts/local-db.sh` | 访问本机 MySQL / Redis | 读取 `config/local-services.env`，避免依赖 PATH。 |

不再保留按模块启动脚本。开发期一般需要后端、前端、Electron、ASR 和搜索能力同时在线，分散入口容易造成状态不一致；临时调试单个模块时，直接进入对应目录运行原生命令即可。

## 构建与打包

| 脚本 | 用途 | 备注 |
| --- | --- | --- |
| `scripts/setup-dev.sh` | 安装开发依赖 | Maven、Electron、前端和 ASR Python 依赖的一次性初始化。 |
| `scripts/build-all.sh` | 构建可打包产物 | 构建 Prime MCP JAR、后端 JAR 和前端 dist；联网搜索已内置在后端。 |
| `scripts/package-all.sh` | 一键打包桌面应用 | 调用构建、准备 ASR 模型、组装 runtime staging，然后运行 electron-builder。 |
| `scripts/download-asr-models.sh` | 下载 SenseVoice 模型 | 写入 `packaging/cache/asr-models/`，通常由 `package-all.sh` 自动触发。 |

## 人设语料流水线

这些脚本用于维护黍的人设与 RAG 语料，不属于日常启动路径。

| 脚本 | 用途 | 输出 |
| --- | --- | --- |
| `scripts/fetch_prts.py` | 从 PRTS 拉取干员页面并清洗人格相关章节 | `data/raw/shu/*.md` |
| `scripts/fetch_huai_shu_li_stories.py` | 抓取“怀黍离”活动剧情 wikitext 并转 Markdown | `data/raw/shu/stories/huai-shu-li/*.md` |
| `scripts/slice_shu_stories.py` | 把活动剧情切成黍出场场景片段 | `backend/src/main/resources/personas/shu/lore/stories/*.md` |
| `scripts/distill_shu_persona.py` | 调用 OpenAI 兼容 LLM 做人设 map-reduce 蒸馏 | `data/processed/shu/persona_distillation/` |
| `scripts/verify_persona_pipeline.py` | 预览 persona、memory、RAG 拼接效果 | `docs/prompt_preview_shu.md` |

## `packaging/` 内容

`packaging/` 是全依赖安装包的运行时组装区，里面既有源码级脚本，也有本地生成的缓存。大文件和生成目录不作为普通源码维护。

| 路径 | 内容 |
| --- | --- |
| `packaging/stage-runtime.sh` | 把后端 JAR、MCP JAR、JRE、MySQL、Redis、ASR、MemOS、SearXNG 等组装到 `packaging/staging/<platform>/`。 |
| `packaging/fetch-runtime.sh` | 说明和辅助准备便携 runtime 缓存；目前主要提示应放入 `packaging/cache/<platform>/` 的目录结构。 |
| `packaging/populate-cache-mac.sh` | 从本机安装或网络下载填充 macOS 打包缓存。 |
| `packaging/init-mysql.sh` | 打包版便携 MySQL 首次初始化辅助脚本。 |
| `packaging/scripts/create-jre.sh` | 使用 `jlink` 生成裁剪版 JRE。 |
| `packaging/config/` | 打包随附配置：初始化 SQL、乱码修复 SQL、运行时配置默认值、MCP 服务器注册表。 |
| `packaging/templates/asr/` | 打包版 SenseVoice ASR 服务、启动脚本、bundle 构建脚本。 |
| `packaging/templates/memos/` | 打包版 MemOS 启动脚本和 venv 构建脚本。 |
| `packaging/templates/searxng/` | 打包版 SearXNG 启动脚本、venv 构建脚本和 settings。 |
| `packaging/cache/` | 本机打包缓存，存放下载或复制来的 runtime、模型、venv 等生成物。 |
| `packaging/staging/` | electron-builder 的 `extraResources` 临时组装目录，由 `stage-runtime.sh` 重建。 |

## 清理原则

- 保留一键入口：开发只认 `start-all.sh` / `stop-all.sh`。
- 保留能被构建、打包、Electron 或后端资源链路使用的脚本。
- 删除解释器缓存、重复模块启动入口和无法解释调用场景的脚本。
- `packaging/cache/`、`packaging/staging/`、模板里的 `venv/` 属于生成物；需要时重建，不在脚本梳理时当成源码入口。
