# Plans

本目录只保留仍然有效的计划索引。旧的分步方案已清理，因为相关功能已经实现或被后续方案覆盖：

- 角色人格/RAG 数据流水线已落到 `backend/src/main/resources/personas/shu/` 与 `RagServiceImpl`。
- Live2D 表情、打断、主动搭话已在前后端实现。
- TTS 已从 GPT-SoVITS / MLX-Audio 方案收敛为 Astra/Genie-TTS。
- 全依赖 dmg/exe 打包方案已沉淀到 `docs/packaging.md`、`scripts/package-all.sh` 与 `packaging/stage-runtime.sh`。

当前待办统一维护在 `docs/TODO.md`。新开较大的功能时，再在本目录新增独立 plan。
