# 测试报告

## 审查基线

既有 Surefire 报告：用户 17、Gateway 6、视频 21，共 44 项通过、无跳过。此前真实 Docker/MinIO/Canal/Kafka/FFmpeg 记录见 VIDEO-VALIDATION.md。2026-10-17 最后视频镜像构建成功。

## 本次变更

以下按阶段记录实际执行结果，最新汇总位于文末。

## 阶段 C/D 新增验证

- `.worker-test.log`：21 个 Checkpoint/MySQL/MinIO 集成测试 + 2 个真实 FFmpeg 测试，23 通过，0 跳过。包括持久任务去重、Lease 心跳、过期接管、旧执行 fencing 和 READY Outbox。
- `.trade-gateway-test.log`：Gateway 原有 6 项 + Redis 实例 4 项、交易 MySQL 9 项、微信 RSA/AES 3 项；22 通过，0 跳过。命令：`mvn -pl video-asset-service,gateway-service -am -Dtest=CommerceIntegrationTest,WechatCryptoTest,GatewayIntegrationTest,TrafficLimiterIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`，附本地 Maven 仓库及 Docker API 1.44 JVM 参数。
- 曾失败：MySQL binlog 下创建触发器权限、测试误用 Outbox 表名、INSERT IGNORE 用户锁的并发死锁、旧 Gateway 测试把新增 orders 路由当成未配置路由。均已修复并在上述测试中通过。
- 后补三项已在阶段 E 通过：待支付上限、支付/关单并发、通知去重与内容冲突。
- 微信真实商户调用、真实渠道退款/提现未执行，不能认定生产联调完成。

## 阶段 E 验证

`.settlement-test.log`：2026-10-18，25 项真实 MySQL 交易/账务集成测试 + 3 项微信密码学测试，28 通过、0 失败、0 跳过。涵盖新增三项交易边界、上架事件、收益解冻、退款乱序、不可变分录、并发提现、失败释放、提现后退款债务及后续抵扣、提现响应丢失恢复、权益插入故障整体回滚、下载权限/退款即时拦截、对账真假状态及金额/缺失差异。Mock 渠道非真实资金。

曾因自动审批用量限制未执行 Docker 命令；用户继续后审批恢复，以上测试已实际执行。生产支付/提现仍未联调。

## 最终回归（2026-10-19）

实际命令：`mvn -B -ntp -Dmaven.repo.local=D:/FrameHub/.tools/repository test`，Java 21，JAVA_TOOL_OPTIONS=-Dapi.version=1.44。`.final-test.log` 于 10:12:34 +08:00 BUILD SUCCESS，耗时 2分43秒。

| 测试组 | 通过 | 失败 | 跳过 |
|---|---:|---:|---:|
| 用户安全 / 用户中心 | 17 | 0 | 0 |
| Gateway 路由 CORS / Redis 限流 | 10 | 0 | 0 |
| Checkpoint / 持久 Worker | 21 | 0 | 0 |
| 素材交易 / 结算 / 并发 / OpenAPI | 27 | 0 | 0 |
| 生产 Mock 配置隔离 | 5 | 0 | 0 |
| 实际 FFmpeg | 2 | 0 | 0 |
| 微信 RSA / AES 密码学 | 3 | 0 | 0 |
| 合计 | **85** | **0** | **0** |

2026-10-18 的全量测试曾因 Windows FFmpeg 日志句柄延迟释放导致 TempDir 删除失败。已修改 ProcessRunner，在超时/取消后终止进程树、等待退出并关闭流；测试还验证日志可在有界时间内删除。以上两项真实 FFmpeg 测试均通过。并发创建 Mock 支付单改为获取排他锁的幂等插入，四线程实测只创建一个渠道支付单。

## 六条真实本地验收（2026-10-18）

命令：`python scripts/commerce-smoke.py`。结果：docs/commerce-smoke-result.json，原始日志 `.commerce-smoke.log`。不是测试桩替代 Canal/MinIO/FFmpeg：

1. 真实 101,215,852 字节 Multipart 上传 → Outbox → Canal → Kafka → Worker → HLS 解码成功 → 素材及协议 → 模拟支付上架费 → PUBLISHED。
2. 买家确认协议下单 → 模拟付款 → 支付事件 → 权益 → 私有原片下载，完整 SHA-256 与上传源一致。
3. 支付收益冻结 → 到期解冻 → 模拟提现成功；重复完成不会重复扣款。
4. 提现后全额退款 → 新下载立即拒绝 → 消费退款事件后权益 REVOKED → 债务 8440 分；账本平衡，渠道对账差异 0。
5. 第一阶段成功后 SIGKILL Worker，新 Worker 接管：executionVersion 1→2，480p 产物 prefix 完全相同，其余阶段继续完成。
6. 实际 Kafka 重复投递两次已提交事件并重启 API；同订单权益、支付流水和解冻流水各一条，余额与分录一致。

## 前端浏览器验证

2026-10-18 实际打开 http://localhost:3000，通过 http://localhost:8080 完成验证码注册、登录和个人资料查询；角色 BUYER 和用户资料来自真实响应。截图 docs/frontend-verified.png。上传/素材/订单/权益/收益/管理页面均接实际 API，但不把 API 脚本验证声称为每个页面都已逐项浏览器自动化验证。

## 部署边界

2026-10-19 实际解析 `docker compose -f compose.yml config --format json`，只保存服务名、端口和 profile 到 docs/deployment-boundary.json。基础 Compose 只有 Gateway 绑定 127.0.0.1:8080；user-service 不直接暴露公网。5 个配置测试覆盖 production/prod/staging 及 local/test 混合生产 profile 的 Mock 拒绝。

真实微信支付、真实退款及真实提现未执行；本地 Mock 的成功不是生产渠道联调成功。生产 TLS、对象存储公开入口（私有桶）、高可用与凭据配置仍是部署前置条件。

## 最终镜像及接口文档（2026-10-19）

全部五个应用镜像构建成功（.final-images.log）。实际 Springdoc 导出 docs/commerce-openapi.json，OpenAPI 路径校验和生产配置隔离再验证共 6 项通过（.openapi-test.log）。前端 `node --check frontend/app.js` 通过，`git diff --check` 通过。临时生成代码脚本已清理，保留可复现的 scripts/ 验收工具。

更新镜像后，视频 API、独立 Worker 和用户服务已运行。Gateway 启动遇到本机 sing-box（PID 28444）持有 0.0.0.0:8080 的 Bound socket，Docker 无法发布 127.0.0.1:8080；不是 user-service 端口外露。未终止其他应用，也未擅自改用其他 API 端口。最新镜像的额外快速验收尚需释放端口后运行，不能用旧验收结果声称这次已通过。

释放后执行：`docker compose -f compose.yml -f compose.local.yml up -d gateway-service`，再运行 `python scripts/commerce-smoke.py --skip-worker-crash --result docs/commerce-smoke-final.json`。完整 Worker 故障恢复的既有证据仍保存在 commerce-smoke-result.json。

## 用户授权改端口（2026-10-19）

用户要求修改网关端口、不修改操作系统端口配置。最终使用 **28080**（候选 18080 被现有 sre-lab Docker 容器映射占用）。Spring Boot、Compose、前端 API/CSP、精确 Origin 配置、API 文档和验收脚本已同步。旧 8080 阻塞已经绕开，未终止 sing-box、未更改系统保留端口或其他项目容器。

Gateway/前端构建及启动成功（.port-build.log、.port-up.log）。实际 HTTP 检查确认 28080 业务入口正常，localhost:3000/config.js 和 CSP 均指向 28080。`python scripts/smoke.py` 通过（.port-user-smoke.log）：CORS、SMTP 验证码、注册、登录 Cookie、资料、创作者、MinIO 直传、刷新和注销撤销。deployment-boundary.json 已重新导出，仅 Gateway 映射 127.0.0.1:28080，user-service 仍无宿主机端口。

新端口最新镜像交易验收已完成：`python scripts/commerce-smoke.py --skip-worker-crash --result docs/commerce-smoke-final.json`，2026-10-19 10:27 +08:00 PASS。实际 5,026,085 字节上传及 HLS 解码、源文件完整哈希一致、上架/购买/授权下载、模拟提现、退款撤权、重复事件和 API 重启恢复全部通过；账本平衡，对账差异 0。该快速复验没有再 SIGKILL Worker；完整故障接管证据保留在 2026-10-18 的 commerce-smoke-result.json。最新本地入口：前端 http://localhost:3000，Gateway http://localhost:28080。
