# Frame Hub 数字视频素材交易平台

Java 21、Spring Boot 3.5.7、Spring Cloud 2025.0.0、MyBatis 3.0.5。沿用三个 Maven 模块：Gateway（28080）、用户中心（8081）、视频资产及交易（8082）。独立 media-worker 容器复用视频模块，通过 MySQL 持久任务执行 FFmpeg；Kafka 消费线程只负责持久化任务。

## 本地启动

需要 Docker Desktop / Docker Compose；本机测试另外需要 Java 21、Maven、FFmpeg/FFprobe，验收脚本需要 Python 3。

```powershell
# 仅首次创建配置；已有 .env 时跳过，不能覆盖已有密码
./scripts/init-local.ps1
docker compose -f compose.yml -f compose.local.yml up -d --build
```

- 前端：http://localhost:3000，真实 API 统一调用 http://localhost:28080。
- Gateway 默认端口 28080，由 GATEWAY_PORT 配置；修改时同步 frontend/config.js、前端 CSP 和 API 客户端地址。前端容器内部的 8080 仅映射到主机 3000，与 Gateway 无关。
- Mailpit 验证码邮件：http://localhost:18025。
- MinIO 控制台：http://localhost:19001；浏览器直传入口 localhost:19000。
- local override 的 MySQL 13306、Redis 16379、Kafka 19092 等开发端口仅绑定回环地址。
- 用户和视频服务不发布宿主机端口，即使本地 Compose 也只能经 Gateway 访问业务 API。

先在前端注册、登录并开通创作者；上传文件后等待 READY，再配置素材与授权协议，支付模拟上架费。买家确认协议后下单、模拟付款、查看权益并下载。收益页面支持冻结余额、流水和模拟提现，管理员页面提供收费规则、退款及对账。

为已经注册的本地测试用户授予管理员权限：

```powershell
./scripts/grant-local-admin.ps1 -Email your-local-test@example.com
```

默认不创建管理员账号，不允许通过注册请求自授角色。Mock 按钮明确标记为模拟资金。默认冻结期 7 天；验收脚本临时创建 2 秒冻结期的收费规则，结束时恢复为原规则的新版本。

## 验证

```powershell
mvn test
python scripts/smoke.py
python scripts/commerce-smoke.py
```

测试使用真实 MySQL、Redis、MinIO Testcontainers；Docker 不可用时容器测试会跳过，不能把跳过当成验收通过。Windows 新版 Docker 如遇 API 兼容问题，设置 `$env:JAVA_TOOL_OPTIONS='-Dapi.version=1.44'`。FFmpeg/FFprobe 放入 PATH 或配置 FFMPEG_PATH/FFPROBE_PATH。

完整交易验收脚本创建本地测试账号和约 100 MB 视频，实际中断 Worker 并验证 Checkpoint 复用，再覆盖上架、购买、下载、收益解冻、提现、退款、重复事件、服务重启和对账。脚本会重启本地业务容器，请在开发环境运行。结果见 docs/commerce-smoke-result.json；逐项证据和最终回归见 docs/TEST_REPORT.md。

## 代码及数据库

- user-service：认证、邮箱、Refresh Cookie、资料、头像、创作者、ADMIN；Flyway V1–V2。
- gateway-service：WebFlux 原路径路由、精确 Origin CORS、Redis 原子令牌桶；无 MyBatis、MySQL 或业务 Controller。
- video-asset-service：分片直传、配额、事务 Outbox、Inbox、媒体任务及阶段、素材/协议不可变版本、订单/支付/退款、权益、账本、提现和对账；Flyway V1–V5。
- frontend：HTML/CSS/JavaScript 真实 API 页面，独立 Nginx 本地容器。
- deploy：MySQL 初始化及 Canal Kafka MQ 输出；scripts：初始化、管理员配置与实际验收。

新增交易数据表集中在 V4/V5：material/material_version/license_version、fee_rule、business_order/payment/payment_notification/refund、entitlement/audit_log、ledger_account/ledger_journal/ledger_entry、earning_freeze、withdrawal_account/withdrawal、reconciliation_batch/reconciliation_difference、Mock 渠道表和消费失败记录。迁移已经应用后不得修改校验和，应新增迁移。

## 配置和生产边界

`.env.example` 列出所有配置，`.env` 不入库。ALLOWED_ORIGINS 是逗号分隔的精确前端 Origin；Cookie 预检由 Gateway 统一处理。USER_SERVICE_URL / VIDEO_SERVICE_URL 支持本机地址或 Compose 服务 DNS。JWT 和角色校验由 user-service 负责；Gateway 只调用内部身份接口获取限流所需的可信用户 ID。

生产仅使用 compose.yml，不加载 compose.local.yml。user-service、video-asset-service、media-worker、MySQL、Redis、Kafka、Canal 均无宿主机端口；默认 Gateway 也仅绑定 127.0.0.1:28080，由部署环境的 HTTPS 反向代理接入。用户与视频业务容器具有出站网络，供 SMTP / 支付 HTTPS 使用，Worker 仅在内部网络。基础 Compose 强制 production profile；支付和提现默认 DISABLED，production/prod 与 local/test 混合时仍拒绝 MOCK。

部署真实渠道时显式配置 PAYMENT_PROVIDER=WECHAT、WECHAT_ENABLED=true、商户信息、API v3 密钥、签名私钥及平台公钥，并开启 COMMERCE_CONSUMER_ENABLED 和 COMMERCE_JOBS_ENABLED。凭据应通过受控文件挂载/环境注入；本仓库未进行真实支付联调，也未实现真实提现渠道，提现默认关闭。禁止用 local override 部署生产。

生产需配置浏览器可达的 HTTPS MinIO S3 入口及精确 CORS，保持桶私有；内部 MINIO_INTERNAL_ENDPOINT 使用服务 DNS，MINIO_ENDPOINT 用于浏览器签名 URL。刷新 Cookie 在生产 Secure=true、SameSite=Strict，前后端应同站点部署。前端 config.js 和 Nginx CSP 当前为本地地址，生产前应一起配置实际域名和 HTTPS。

单节点 Compose 用于可运行验证，不代表生产高可用。生产数据库迁移权限、备份、Kafka 副本、监控告警、TLS 和支付商户资质需由部署环境落实。没有 Nacos、Kubernetes 或额外配置中心。

## 文档

- [实施状态](docs/IMPLEMENTATION_STATUS.md)、[实施顺序](docs/IMPLEMENTATION_PLAN.md)
- [架构决策](docs/ARCHITECTURE_DECISIONS.md)、[API](docs/API.md)、[视频流水线](docs/VIDEO.md)
- [测试报告](docs/TEST_REPORT.md)、[用户 OpenAPI](docs/openapi.json)

视频交易 OpenAPI 可由 CommerceIntegrationTest 实际启动 Springdoc 导出，命令见 API 文档；生产默认关闭在线文档。
