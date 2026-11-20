# 启动、打包与部署

## 版本称呼与最短命令

只有两个版本：**商业版**和**离线版**。商业版分为**用户端**（Electron 桌面）和**管理端**（浏览器网页），二者共用商业后端；离线版为桌面、业务 API、本机模型与语音运行时组成的整体。`cloud/offline/admin` 是代码中的构建标识，`cloud` 不是第三种版本。

在仓库根目录执行：

| 目标 | 一条命令 | 结果 |
| --- | --- | --- |
| 离线版本地测试 | `npm run dev:offline` 或 `npm run dev` | 构建界面和离线 JAR，启动桌面及本机 API |
| 商业版本地测试 | `npm run dev:commercial` | 启动开发基础服务、商业 API、用户端，输出管理端地址 |
| 管理端本地测试 | `npm run dev:admin` | 同一套商业后端与基础服务，自动打开管理端网页 |
| 商业版用户端完整打包 | `npm run package:commercial:user` | 构建界面后生成当前系统的桌面安装包 |
| 商业版管理端完整打包 | `npm run package:commercial:admin` | 构建管理网页与商业 JAR，生成独立部署压缩包 |
| 离线版整体完整打包 | `npm run package:offline` | 构建界面、离线 JAR，刷新/组装原生运行时，生成安装包 |

`dev:cloud`、`package:cloud` 是商业版旧命令的兼容别名。开发命令针对源码验证；用户启动已经安装的应用无需 Node/JDK。打包默认 `--publish never`，不会自动发布。

## 开发环境与数据

需要 Node 24 和 JDK 17+，Maven 通过 `tools/maven` 的 wrapper 执行。开发入口发现缺少 Vite 时自动在 `apps/desktop` 执行 `npm ci`；首次安装和 Maven 解析需要联网。依赖锁变化时主动执行 `npm run setup` 更新依赖。

离线版开发直接使用系统 JDK 和本次构建的 JAR，不再要求事先手工 staging Java。原生运行时若已准备且已导入模型包，则同时启动聊天、嵌入和语音。没有模型也能进入角色库、编辑故事与连接自有本地模型；实际回复、索引和语音需要各自可用的模型服务。应用不会自动下载权重。

macOS 开发入口会在启动 Electron 前运行 `apps/desktop/electron/prepare-macos-location.js`，补齐位置用途说明并校验开发 app 签名。若该路径的客户端仍在运行会要求先关闭，不会原地修改正在使用的 app。准备只处理开发 bundle，不修改系统隐私开关；正式包继续由 electron-builder 的用途说明和发行签名配置处理。设备定位仍需用户正常授予系统权限。

商业版开发另需 Docker Engine / Desktop 及 Compose v2，且 Docker 已运行。首次启动自动拉取 pgvector、Mailpit，按固定的官方源码版本构建 MinIO，并构建受限出站代理与 S3 初始化工具（首次耗时较长，后续复用镜像）；启动完成后输出本次随机端口上的管理端、开发邮箱地址。API 与基础服务仅绑定宿主回环地址。Mailpit 接收本地验证码邮件，不向真实邮箱投递。平台模型端点仍需通过 `STORY_LLM_*`、`STORY_EMBEDDING_*`、`STORY_ASR_*`、`STORY_TTS_*` 配置；开发命令不会伪造模型回复。

已有开发镜像且镜像仓库暂时无法访问时，可用 `STORY_DEV_REUSE_IMAGES=1 npm run dev:commercial` 复用 S3 初始化工具镜像。修改初始化工具源码后应取消该变量以重新构建；此选项不会跳过后端或前端构建。

- 管理员邮箱、随机密码和 TOTP 密钥保存在 `.local/dev/commercial-secrets.json`，仅本机用户可读；`npm run dev:otp` 输出当前动态验证码。管理端密码 + MFA 登录后创建邀请码，在开发邮箱里读取注册验证码来测试用户注册。
- 默认开发目录：离线 `.local/dev/offline/`；商业 `.local/dev/commercial/`。安装版数据在 Electron userData 的 `story-space-offline/` 或 `story-space-cloud/`。两者隔离。
- 如显式设置 `STORY_DEV_SQLITE_FILE` 或 `.local/dev/offline-database.json` 的 `file`，离线开发可连接指定的既有 SQLite；这是手动例外，运行前应自行备份。`--smoke` 始终忽略该映射，只使用专用开发库。
- 商业开发容器项目名 `story-space-dev`，数据库与图片保存在该项目专用 volumes，与生产 Compose 的 `story-space` 分开。
- Ctrl+C 或关闭用户端会停止本次启动的进程与商业开发容器，保留测试数据。管理端浏览器关闭后，在终端 Ctrl+C 停止。
- 同一工作区一次运行一个开发命令，避免同时覆盖 Maven target。异常断电留下的锁会在下一次发现原 PID 不存在时清理。
- 未打包开发模式仅允许指定的本机 HTTP 服务。生产用户端仍要求 HTTPS。

自动检查入口（执行后自行停止）：

```bash
npm run dev:offline -- --smoke
npm run dev:commercial -- --smoke
npm run dev:admin -- --smoke
```

这些检查使用专用开发库，忽略离线既有数据库映射，验证前端构建、后端健康与认证；不等于真实模型、麦克风或安装包 UI 验收。`--no-open` 可阻止管理端自动打开浏览器。

## 打包的前提与产物

可用 `STORY_CLOUD_URL` 指定商业版用户端的默认 HTTPS 服务地址；留空则由用户在连接界面填写。

商业版用户端打包只需要 Node 和前端依赖；管理端部署包另需 JDK 和 `tar`。离线版打包若已有对应平台完整 staging，只需 Node、JDK、Python 3（Windows 命令为 `python`）；每次都重新构建并替换 JAR，避免把旧业务代码打进去。

首次离线原生组装还需要 JDK 的 `jlink`、Python 3.12、`llama-server` 和 `ffmpeg` 在 PATH；脚本会自动创建 `.local/build/voice-venv`、安装语音构建依赖、冻结 story-voice、裁剪 JRE 并调用 stage-runtime。已有对应平台运行时则跳过这些原生编译步骤。Windows 的语音运行时必须在 Windows 上冻结。跨平台打包必须先准备目标平台 staging；不能把 macOS 二进制改扩展名变成 Windows 程序。

| 产物 | 位置与内容 |
| --- | --- |
| 商业版用户端 | `apps/desktop/release-cloud/`；界面和受控 IPC，不含业务服务、数据库或权重 |
| 商业版管理端 | `artifacts/StorySpace-commercial-admin.tar.gz` 与展开目录 `artifacts/commercial-admin/`；管理网页内嵌的 JAR、Dockerfile、Compose、Caddy/Squid 配置、无凭据环境模板、部署 README 和 JAR 校验值 |
| 离线版 | `apps/desktop/release-offline/`；界面、最新离线 JAR、JRE、llama.cpp、语音与 ffmpeg，不含商业运营代码及模型权重 |

可附加目标参数；macOS 默认 DMG，Windows 默认 NSIS：

```bash
npm run package:commercial:user -- --mac --publish never
npm run package:commercial:user -- --win --x64 --publish never
npm run package:offline -- --mac --publish never
# 只生成展开目录，便于验收；离线包仍包含完整运行时
npm run package:offline -- --dir
npm run package:commercial:admin -- --dir
```

旧 `scripts/release/build.sh offline|cloud mac|win` 与 `scripts/package-all.sh` 转发到相同的完整流程。底层 `electron/package-release.cjs` 只调用 electron-builder，供编排入口使用。源码导出只保留离线命令。

## 离线模型包

1. `fetch-models.py` 由发行方获取 Qwen3-4B Q4、Qwen3-Embedding-0.6B Q8、SenseVoiceSmall、Kokoro 与词典，记录来源与许可；默认模型约 3.6GB。
2. `sign-model-pack.py <模型目录> --spec packaging/release/model-pack.example.json --key <发行私钥>` 生成清单、大小、SHA-256 与 Ed25519 签名。应用公钥为 `apps/desktop/electron/model-pack-public.pem`，私钥保存在仓库外。
3. 用户在离线设置选择已解压的签名模型目录导入。自建发行方应更换公钥并自行签名。测试开发目录与安装版数据隔离，模型包需分别导入。

需要自行指定二进制路径时，仍可用 `stage-runtime.py darwin|win32 --java <目录> --llama <程序> --voice <冻结目录> --ffmpeg <静态程序>`；它只接受离线 JAR。模型权重与运行时各自遵守许可，完整打包不表示附带全部权重。

## 商业版部署

部署包解压后按包内 README 填写 `.env`，执行 `docker compose up -d --build`。包含 API、PostgreSQL/pgvector、MinIO、受限出站 Squid、Caddy；数据库和对象服务不开放公网端口，API 仅绑定宿主回环 8081，公网入口由 Caddy 处理。管理端路径 `/admin/`，用户端连接同一个 HTTPS 域名。

也可从源码部署：`python3 scripts/release/init-cloud.py` 生成权限 600 的私有 `deployment/cloud/.env`；补全真实配置后执行 `docker compose -f deployment/cloud/compose.yml up -d --build`。源码 Dockerfile 构建管理网页和商业 JAR，不依赖本机预先 build。

| 环境变量 | 用途 |
| --- | --- |
| STORY_DATABASE_URL / USER / PASSWORD | PostgreSQL |
| STORY_S3_ENDPOINT / BUCKET / ACCESS_KEY / SECRET_KEY / REGION | 对象存储 |
| STORY_ADMIN_EMAIL / PASSWORD / TOTP_SECRET | 管理员初始化与 MFA；已有管理员密码不会被启动时覆盖 |
| STORY_ENCRYPTION_KEY、STORY_SIGNING_KEY | 32 字节 Base64 加密密钥、至少 32 字符签名密钥；单独备份 |
| STORY_SMTP_HOST / PORT / USER / PASSWORD、STORY_MAIL_FROM | 注册邮件 |
| STORY_LLM_URL / KEY / MODEL / VISION | 平台聊天及图像能力 |
| STORY_EMBEDDING_URL / KEY / MODEL | 平台嵌入；schema 固定 1024 维 |
| STORY_ASR_URL / MODEL、STORY_VOICE_KEY | 后端 ASR 端点与密钥 |
| STORY_TTS_ENGINE、STORY_ASTRA_URL | 独立后端默认 astra；完整离线包默认 Kokoro，显式引擎/地址优先；Astra 地址默认 http://127.0.0.1:5001 |
| STORY_TTS_URL / MODEL / VOICE | Kokoro 兼容 speech 端点、模型及默认音色；离线包自动配置本机端点 |
| STORY_SEARCH_URL | 后端 SearxNG 服务地址，默认 http://127.0.0.1:8888 |
| STORY_BYOK_PROXY_HOST / PORT | 自带模型的受限出站代理 |
| STORY_ORIGINS | 允许的浏览器/App origin |
| STORY_DAILY_LIMIT、STORY_DAILY_CUSTOM_LIMIT、STORY_MAX_STREAMS | 免费额度与并发 |

离线版由 Electron 自动生成 STORY_LOCAL_TOKEN、STORY_PORT、STORY_DATA，并强制 offline 与回环地址，端口不能缓存为持久配置。开发入口自动注入的凭据、端口和邮箱仅供本地测试，不用于生产部署。

离线后端支持 `STORY_SQLITE_FILE` 单独指定数据库文件，未设置时使用 `STORY_DATA/stories.sqlite`。桌面发行固定使用自身数据目录；本地开发可通过私有配置选择与 Navicat 共用的文件，见 [工具入口](scripts.md#用-navicat-查看本地测试的实时数据)。

## 源码、验证与许可

`npm run build` 运行前端检查、构建三种界面、将管理网页放入商业 JAR，再 clean 构建离线 JAR；商业 JAR 另存 `artifacts/story-cloud-runtime.jar`。日常 `dev` / `package` 会自动构建，不要求先执行这个全量验证命令。

`npm test` 执行前端和离线 Java 回归。`STORY_TEST_PG` 只可指向专用 PostgreSQL fixture 库。`native-smoke.cjs` 在隔离目录执行真实模型、ASR/TTS，不能使用私人故事做压测。

`python3 scripts/release/export-source.py` 生成 `artifacts/StorySpace-local-source.tar.gz`。白名单只含离线源码、共享 RP、wrapper 与必要工具，剔除 cloud Java、商业配置、迁移、管理网页、个人数据和第三方素材。实际导出目录也应执行依赖安装、Vite build 和 Maven offline verify。

自有代码 Apache-2.0。第三方二进制、模型及其源码义务遵循各自许可；`packaging/release/NOTICE` 和 `LICENSE` 随应用分发。插画来源见 `release-public/release-art/provenance.json`。签名公证、Windows/16GB 实机和公网恢复验收见 TODO；本地测试通过不表示已完成公开发行验收。

## 黍个人版与本机语音联调

商业桌面始终访问商业 Java 后端的 `/api/chat`（含音频输入）和 `/api/v1/voice/speech`。本机验收启动相同商业后端并连接本机 ASR/AstraTTS；上线时将环境变量配置为服务端可达的语音服务地址。商业客户端不获得这些地址或平台语音密钥。

离线签名包仍可提供 SenseVoiceSmall ASR；黍使用已有 Astra 服务及 Shu_2 音色，需先启动服务。显式 STORY_ASR_URL、STORY_LLM_URL、STORY_EMBEDDING_URL 可复用已有本机服务，即使安装了模型包也不会重复启动相应进程。Astra 优先使用 5001；仅离线版在该默认地址不可用时兼容探测回环 5000，并校验实际返回的音色列表，避免把 AirPlay 当作语音服务。显式其他地址和商业服务端不会自动改用备用地址。配置示例：

```bash
export STORY_ASR_URL=http://127.0.0.1:9000/v1/audio/transcriptions
export STORY_TTS_ENGINE=astra
export STORY_ASTRA_URL=http://127.0.0.1:5001
python3 scripts/release/stage-shu.py
STORY_PERSONAL_SHU=1 npm run package:offline -- --mac --dir
# 商业版同样使用 STORY_PERSONAL_SHU=1，并设置实际 STORY_CLOUD_URL
```

个人 pack 写 artifacts/shu-personal-pack，仅含原角色资料/模型/Core/动作/表情和旧技能、安全 Prime 示例，不含故事或任意工具密钥。默认通用构建不带这些私人素材。Astra 与私人音色当前单独运行，不作为通用签名模型包的自动下载内容。
