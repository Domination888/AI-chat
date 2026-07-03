# AI 日报 Skill 主动话题功能计划

## 背景

希望应用每天定时读取「AI 日报」，并把日报内容作为 AI 主动对话的话题来源。日报来源当前明确为：

- RSS 文字版：https://daily.juya.uk/rss.xml
- GitHub 上的 Markdown 文件：作为 RSS 不稳定或需要完整正文时的备选读取源
- ClawHub 搜索 `juya` 已有相关 Skill：可作为后续外部 skill 适配来源，但本项目不应强依赖它才能工作

现有项目已经有：

- `config/skills/*/SKILL.md`：manifest 式 skill，可通过扩展管理启停
- `SkillService` / `SkillRuntimeService`：读取 skill，并把启用 skill 注入系统提示
- `ProactiveChatService`：空闲后主动搭话，通过 SSE 推送到前端
- 前端 `McpSkillManager.vue`：可启停/编辑 skill

## 目标

1. 新增一个可开关的 `ai-daily-juya` skill。
2. skill 启用时，后端按配置每天定时读取 AI 日报。
3. 成功读取后，把日报摘要/看点作为主动对话话题推给当前活跃会话。
4. 关闭该 skill 后，停止读取与主动触发，不影响普通主动搭话。
5. 改动保持在 skill 维度，避免把「AI 日报」硬编码进通用聊天逻辑。

## 非目标

- 不做完整新闻客户端、历史日报浏览器或收藏功能。
- 不把 RSS 内容写入数据库作为长期知识库，第一版只做短期缓存和去重。
- 不自动安装 ClawHub skill；第一版保留可插拔边界，后续再接入。
- 不改变现有 weather/prime/web-research 的行为。

## 建议实现边界

### 1. 扩展 SkillManifest 的可选配置

在 `SkillManifest` 上新增可选字段，旧 `SKILL.md` 无这些字段时保持默认兼容：

```yaml
schedule:
  enabled: true
  cron: "0 0 9 * * *"
  zone: "Asia/Shanghai"
source:
  type: "rss"
  url: "https://daily.juya.uk/rss.xml"
  fallbackMarkdownRepo: ""
proactive:
  enabled: true
  topicMode: "daily_digest"
  maxItems: 5
  promptTemplate: |
    [System: 你刚读完今天的 AI 日报。请挑 1-2 个和用户可能相关的看点，自然主动开个话题。不要逐条播报全文。]
```

实现时建议把这些配置建成嵌套 POJO，例如 `ScheduleConfig`、`SourceConfig`、`ProactiveConfig`，并让 `SkillService.parse/render` 支持读写。前端编辑器第一版可以只展示 JSON/YAML 高级配置区，避免为了一个 skill 重做复杂表单。

### 2. 新增 AI 日报读取服务

新增后端服务建议命名：

- `AiDailySkillService`
- 或 `ScheduledSkillService` + `AiDailySourceReader`

职责：

- 只处理启用的 `ai-daily-juya` skill。
- 使用 JDK XML 解析 RSS，不新增依赖；读取标题、链接、发布时间、摘要。
- RSS 失败时再尝试 GitHub Markdown 源；如果 GitHub 路径未配置，则只记录失败并等待下次调度。
- 对同一天/同链接做去重，避免重启或重复调度时多次主动搭话。
- 缓存最近一次日报结果到内存即可；如要跨重启去重，可在 `AppPaths.configDir()` 下写轻量状态文件，例如 `config/skills/ai-daily-juya/state.json`。

### 3. 新增通用主动话题入口

当前 `ProactiveChatService` 的触发点只有：

- 用户空闲定时触发
- 前端点击 Live2D 立即触发

建议新增一个内部方法：

```java
boolean triggerTopic(String conversationId, ProactiveTopic topic)
```

`ProactiveTopic` 至少包含：

- `sourceSkill`
- `title`
- `summary`
- `links`
- `prompt`

然后复用现有 SSE/TTS 生成链路，只把 `ChatRequest.message` 设置为日报话题 prompt。这样 AI 日报不会污染普通主动搭话配置，也不需要前端理解日报内容。

### 4. 活跃会话选择策略

第一版建议只推给「已注册主动搭话且 SSE 仍连接」的会话。理由：

- 这和现有主动搭话的用户授权边界一致。
- 前端不在线时不主动生成，避免后台无接收者消耗 LLM/TTS。
- 不需要新增通知中心。

如果多个会话都活跃，第一版可以全部尝试触发，但受 `ProactiveChatService` 的 generating/active sink 检查保护；或只选最近交互的会话。实现前需要最终确认策略。

### 5. 新增内置 SKILL.md

在 `config/skills/ai-daily-juya/SKILL.md` 新增默认关闭或默认开启需要确认。建议默认关闭，避免用户不知情联网读取并主动发起话题。

建议正文：

- 何时使用：每天定时读取 AI 日报，作为主动话题来源。
- 输出要求：只挑重点，不播报全文；保留来源链接；不把未读到的内容当事实。
- 失败策略：读取失败不主动开场，不编造日报。

同时在 `SkillService.init()` 里增加 `seedAiDailyIfMissing()`，保证新安装环境能看到该 skill。

### 6. 前端扩展管理

最小改动：

- skill 列表展示 schedule/proactive 状态标签，例如 `定时`、`主动话题`。
- 编辑 skill 时保留新增配置字段，不因保存丢失。
- 可选增加「立即读取/测试」按钮，对应后端测试接口。

第一版可以不把每日时间做成专用控件，只要用户可以启停 skill。后续再做更友好的时间选择。

### 7. 后端接口

建议新增调试接口，便于验证：

- `POST /api/skills/ai-daily-juya/test-fetch`：读取 RSS/Markdown，返回解析到的条目。
- `POST /api/skills/ai-daily-juya/trigger`：读取并向当前已注册主动会话触发一次话题。

如果不想把接口绑死 skill 名，可设计为：

- `POST /api/skills/{name}/actions/test-fetch`
- `POST /api/skills/{name}/actions/trigger`

第一版为了少改动，可以先用专用接口；但从 skill 维度看，通用 action 接口更干净。

## 数据流

1. 应用启动后 `SkillService` 读取 `ai-daily-juya` manifest。
2. `AiDailySkillService` 监听/定时检查该 skill 是否启用。
3. 到达 cron 时间后读取 RSS。
4. 解析并生成简短 digest。
5. 去重检查通过后，为每个候选会话调用 `ProactiveChatService.triggerTopic(...)`。
6. 前端沿用现有 proactive SSE，消息正常进入聊天列表并播放 TTS。

## 风险与处理

- RSS XML 格式变化：解析只依赖 `item/title/link/pubDate/description`，缺字段时降级。
- 外网不可达：记录日志，不主动话题，不阻塞应用启动。
- 日报太长：读取阶段限制条目数量和摘要长度，prompt 中明确「挑 1-2 个看点」。
- 重复打扰：按日期或 RSS 最新 link 去重；并复用主动搭话的活跃生成检查。
- 用户关闭 skill：调度服务每次触发前重新读取 enabled 状态，关闭后不再读取/触发。
- 隐私/授权：只向已开启主动搭话且存在 SSE 连接的会话推送。

## 测试计划

后端单元/集成测试：

- `SkillService` 能解析/render 新增 `schedule/source/proactive` 配置，旧 skill 不变。
- RSS 样例 XML 能解析标题、链接、摘要、日期。
- RSS 失败时 fallback 不会抛出未捕获异常。
- disabled skill 不会触发读取。
- 同一天/同 link 不重复触发。

手工验证：

- 在扩展管理里能看到 `ai-daily-juya`，开关可用。
- 开启主动搭话和该 skill 后，调用测试触发接口，前端收到一条主动消息。
- 关闭该 skill 后，再调用调度/测试触发不会生成主动消息。
- 普通聊天、天气 skill、点击 Live2D 主动说话仍正常。

## 需要确认的问题

1. 默认开启状态：`ai-daily-juya` 是否默认关闭？我建议默认关闭，由你手动开启。
2. 每天读取时间：默认是否用 `Asia/Shanghai` 的 09:00？
3. 多活跃会话策略：日报话题是发给所有已注册主动搭话的会话，还是只发给最近交互的当前会话？
4. GitHub Markdown 源：请确认具体仓库/路径。如果不确认，第一版只实现 RSS，保留 fallback 配置位。
5. ClawHub `juya` skill：第一版是否只做兼容边界，不自动依赖/安装？

## 推荐第一阶段落地顺序

1. 扩展 `SkillManifest` 与 `SkillService` 解析/保存。
2. 新增 `ai-daily-juya` 内置 skill，默认关闭。
3. 新增 RSS 读取与 digest 生成服务。
4. 给 `ProactiveChatService` 增加 `triggerTopic` 内部入口。
5. 新增测试/手动触发接口。
6. 前端技能编辑保留新增配置字段，并展示状态标签。
7. 补测试和手工验证。
