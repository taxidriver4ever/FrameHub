# 架构决策

## 2026-10-18 本次需求优先级

本次提示词明确恢复 Worker Lease/Heartbeat，并要求 Kafka 不阻塞于 FFmpeg，取代上一版同步 Consumer 的约束。复用现有 pipeline、任务、阶段和版本化产物，不重新实现上传/用户服务。

## 服务边界

继续使用 gateway-service、user-service、video-asset-service。素材、交易、授权和账务位于视频应用的 commerce 包，与素材/资产处于同一 MySQL 库，使用明确本地事务，避免新增跨服务分布式事务。应用名称保留以兼容现有部署；Worker 使用同一代码库的可配置后台角色，可单独部署运行。

## 消息

沿用 Canal 自带 Kafka MQ 输出，不叠加 Bridge。统一 outbox_event 增加 aggregate_type/aggregate_id/trace_id；按 aggregate_id 分区。消费者以 Inbox 或唯一任务去重，在本地事务后 ACK；媒体副作用由持久任务 Worker 负责。不是端到端 Exactly-Once。

## 资金与外部环境

整数分、BPS、显式 HALF_UP 舍入和受限金额；订单固化费用及协议快照。平衡账本保存不可变多分录，余额汇总与分录同事务。支持全额退款，部分退款不在首个闭环范围，接口明确拒绝不匹配金额。提现后退款形成追偿债务，后续收益先抵债，不能删除历史流水。

本地 Mock 支付/提现必须标识为模拟，生产禁止模拟成功入口。真实微信支付只实现可配置安全适配层，未提供真实商户凭据不发起真实资金操作。不会修改现有真实密钥或账户。

## 交易状态与接口限流

订单：PENDING → PAYING → PAID，取消先进入 CLOSING，只有渠道确认关闭后进入 CANCELLED；收到取消后的成功付款则进入 REFUND_PENDING 自动补偿。退款为 PAID → REFUND_PENDING → REFUNDED；渠道明确退款失败可恢复 PAID。外部请求不持有业务数据库事务，渠道单号幂等且可主动查询恢复。

支付单 UUID 去掉连字符作为微信 out_trade_no（32 字符），回调还原 UUID。微信官方 Native API v3、RSA-SHA256 验签、AES-256-GCM 解密，固定 HTTPS 官方域名，不允许任意支付 URL。参考：[Native 下单](https://pay.wechatpay.cn/doc/v3/merchant/4012791877)、[退款回调](https://pay.wechatpay.cn/doc/v3/merchant/4012791906)、[下载账单](https://pay.wechatpay.cn/doc/v3/merchant/4013071238)。平台公钥 ID 与公钥由运维配置并轮换；未知 ID 拒绝，不能降级跳过验签。真实渠道联调仍未执行。

Gateway 使用 Redis Lua 原子令牌桶，Redis TIME 保证多实例时钟一致，用户身份向 user-service 校验，不在网关重复 JWT/角色规则。IP 总桶、用户总桶及用户+方法+规范化接口桶共同控制流量；OPTIONS 由统一 CORS 处理，Redis 故障返回 503。普通业务接口不能依赖限流代替数据库约束。

素材和协议版本禁止 UPDATE。MySQL 启用 binlog 时迁移创建不可变触发器需要 log_bin_trust_function_creators=1；本地 Compose 和 Testcontainers 显式设置，不向应用授予 SUPER。既有生产实例需由数据库运维审核迁移权限，不自动连接或修改。

## 结算、退款和对账边界

渠道账单确认的实际手续费与订单预计手续费差额由平台账户调整，订单快照不改写。全额退款时平台承担未返还的渠道费用；退款冲正使用新流水。提现中余额不会被退款直接扣走，形成债务；提现失败释放时先抵债。后续收益同样优先抵债。账本校验只报告不平衡/汇总差异，不提供任意改余额入口。

上传的测试账单仅是对账线索，不能凭文件把订单变成已付款；自动补偿必须再次向渠道查单。差异确认只追加人工处理说明和审计，不代表财务差异已经自动消除。同一账单可重跑，以覆盖延迟到达的本地状态。

下载链接默认 2 分钟；退款立即禁止签发新链接，已签发的 S3 URL 在原 TTL 内仍可能访问，不能声称实时吊销 URL。

## Outbox 归档与运维

Canal 使用自带 Kafka MQ 输出：Broker 确认发送后提交 CDC 批次，失败保留进度重试；崩溃窗口允许重复，依靠稳定 event_id 与事务 Inbox 去重。消费失败记录持久化成功后才 ACK，由恢复任务重试。需监控 Canal 位点延迟、Kafka Lag、待重试任务、过期 Lease、消费失败和对账 OPEN 差异。

Outbox 首期不自动删除。建议至少保留 90 天；归档前校验全部消费组已越过候选事件位点、无关联未解决失败，生成可校验备份并演练重放，再分批删除已归档事件。Inbox 去重记录与可重放事件保持同等或更长保留期；财务分录、协议及订单快照按业务留存政策独立保留，不能跟随 Outbox 清理。超过 Binlog/Kafka 保留窗口必须从备份/已审计 Outbox 恢复，不能直接跳到最新位点。

## 本地网关端口调整（2026-10-19）

用户明确授权将 Gateway 从 8080 改为 28080，避开 sing-box 的占用。Spring Boot 默认端口及 Compose 主机/容器端口一致由 GATEWAY_PORT 配置，前端 API/CSP 和验收脚本同步调整。未修改操作系统端口范围、防火墙或代理配置，也未终止代理。历史验收中的 8080 保留为当时事实。
