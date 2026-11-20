# 商业版管理端部署包

这个包包含管理端网页、商业版共用 Java 后端、Dockerfile、Compose、反向代理配置和环境变量模板。用户端连接同一个后端；管理端不另建一套账号或故事数据库。部署机无需项目源码。

需要 Docker Engine 与 Compose v2、可用域名，以及真实 SMTP、模型服务。解压后在本目录执行：

```bash
cp .env.example .env
# 按下方说明填好 .env 后启动：
docker compose up -d --build
```

`.env` 填写管理员邮箱、16 字符以上密码、至少 16 字节的 Base32 TOTP 密钥、数据库密码、S3 凭据、SMTP、模型端点和域名。`STORY_ENCRYPTION_KEY` 必须是 32 个随机字节的 Base64；`STORY_SIGNING_KEY` 至少 32 个随机字符。可用 `openssl rand -base64 32` 分别生成加密密钥和签名密钥；不要把相同值复用为不同用途的密钥。TOTP 密钥导入自己的验证器。将 `.env` 权限设为仅自己可读，备份到私有位置。

管理端入口 `https://你的域名/admin/`。商业版用户端填写 `https://你的域名`。管理员需要密码和动态验证码。数据库与对象存储保存在 Docker volumes，`docker compose down` 保留这些数据。备份应同时保存 PostgreSQL、对象数据和密钥；删除 volumes 会删除数据。

`SHA256SUMS` 校验后端 JAR。安装包、模型权重、真实凭据、数据库和个人故事不包含在本包内。正式部署前仍需完成域名、TLS、SMTP、模型服务和恢复演练。
