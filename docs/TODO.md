### 近期优化：
- 记忆 + memos，现在每次都要sum history，这样肯定不对(claude)(次重要)
- tts调优，到底为什么ttfb那么高？SSE返回失败了(claude)(最重要)
- 重构prompt，优化口癖，别带口癖(claude)
- 
### 未排期优化：
- 双py版本导致内存占用过多(claude)
- 数据库回读时表情露出(GLM)
- mcp的联网搜索未测试
- tools和mcp还是该启动时校验
- 真打包，怎么打包成全依赖的exe和dmg？
- 拓展skill

### 待测试：
- 打断是否生效

### 项目模块：
TTS
ASR
Live2D控制
主动对话
人格组成
记忆管理

### 已完成：
- 前端设置不生效
- 深色模式
- 情绪标签控制Live2D
- 表情重置不对（5.22，codex5.2轻松解决GLM5.1的问题）