# 用户中心 API

入口：`http://localhost:28080`；服务本地监听 `127.0.0.1:8081`。
本机 Java 模式（不是 Compose 端口映射）自动生成 OpenAPI：`http://localhost:8081/v3/api-docs`；Swagger UI：`http://localhost:8081/swagger-ui/index.html`。
`docs/openapi.json` 为实际服务导出的离线快照，补充了网关地址与 Bearer 安全方案，可直接导入 API 工具。生产默认禁用文档，不经网关发布文档。统一响应 `{ "code": 0, "message": "success", "data": ... }`。
ID 一律为 JSON 字符串；时间为 UTC ISO-8601。错误 HTTP 状态和业务码均为 400/401/403/404/409/429/500。

| 方法 | 路径 | 权限 | 请求 / 说明 |
|---|---|---|---|
| POST | /auth/email-code | 公开 | `{"email":"user@example.com","scene":"REGISTER"}` |
| POST | /auth/register | 公开 | `{"email":"user@example.com","password":"Abc123456!0","verificationCode":"826391","nickname":"视频创作者01"}`；201，不自动登录 |
| POST | /auth/login | 公开 + 可信 Origin | `{"email":"user@example.com","password":"Abc123456!0"}` |
| POST | /auth/refresh | Refresh Cookie + 可信 Origin | 无 Body，轮换 Cookie |
| POST | /auth/logout | Bearer + 可信 Origin | 无 Body，撤销当前会话和 JTI，清除 Cookie |
| POST | /auth/password/forgot | 公开 | `{"email":"user@example.com"}`；始终相同提示 |
| POST | /auth/password/reset | 公开 | `{"resetToken":"邮件链接中的凭证","newPassword":"NewPass123!"}` |
| GET | /users/me | Bearer | 当前用户资料 |
| PATCH | /users/me | Bearer | `{"nickname":"新昵称","bio":"新简介"}` |
| PUT | /users/me/password | Bearer | `{"currentPassword":"Abc123456!0","newPassword":"NewPass123!"}` |
| POST | /users/me/avatar/upload-url | Bearer | `{"filename":"avatar.png","contentType":"image/png","sizeBytes":102400}` |
| PUT | /users/me/avatar | Bearer | `{"objectKey":"上传凭证返回的objectKey"}` |
| POST | /creators/me/activate | Bearer | `{}`；幂等开通，保留 BUYER |
| GET | /creators/me | CREATOR | 自己的完整创作者资料 |
| PATCH | /creators/me | CREATOR | `{"displayName":"工作室","bio":"风景素材"}` |
| GET | /creators/{creatorId} | 公开 | 公开主页，不含 email、userId、status、权限 |

## 前端调用

```javascript
const base = 'http://localhost:28080';
const login = await fetch(`${base}/auth/login`, {
  method: 'POST', credentials: 'include',
  headers: {'Content-Type': 'application/json'},
  body: JSON.stringify({email: 'user@example.com', password: 'Abc123456!0'})
}).then(r => r.json());
const profile = await fetch(`${base}/users/me`, {
  headers: {Authorization: `Bearer ${login.data.accessToken}`}
}).then(r => r.json());
const refreshed = await fetch(`${base}/auth/refresh`, {
  method: 'POST', credentials: 'include'
}).then(r => r.json());
```

浏览器自动发送 Origin；curl/脚本访问 login、refresh、logout 也必须携带明确允许的 Origin，例如 `Origin: http://localhost:3000`。Gateway 统一处理预检及允许来源，认证头、请求体、Cookie、Set-Cookie 和原始路径透传。
Refresh Cookie：HttpOnly、Path=/auth、SameSite=Strict；生产 Secure=true，本地 profile 为 HTTP 测试关闭 Secure。生产前后端应在同站点部署；不支持以跨站 Cookie 绕过 SameSite。

登录成功示例：
```json
{"code":0,"message":"登录成功","data":{"accessToken":"<jwt>","tokenType":"Bearer","expiresIn":900,"user":{"userId":"1987654321098765432","nickname":"视频创作者01","roles":["BUYER"]}}}
```
个人资料示例：
```json
{"code":0,"message":"success","data":{"userId":"1987654321098765432","email":"user@example.com","nickname":"视频创作者01","bio":null,"avatarUrl":null,"roles":["BUYER"],"status":"ACTIVE","createdAt":"2026-10-17T02:30:00Z"}}
```
头像上传返回 `{objectKey,uploadUrl,expiresIn:300}`。使用 `PUT uploadUrl`，Body 为图片二进制，Content-Type 与申请值一致，然后调用头像确认接口；不要使用 multipart/form-data 上传。确认检查归属、存在性、精确大小、MIME 与魔数，以 ETag 约束读取和复制到不可被原上传 URL 覆盖的私有对象。下载链接同样只有效 300 秒。
PATCH 未提交字段保持不变；bio:null 清空简介，nickname/displayName 不可为 null 或空白。敏感字段及未知字段拒绝。

## 会话与并发约定

Access Token 15分钟；Refresh 会话族绝对有效期7天（刷新不延长）。Redis Lua 原子轮换，旧 token 的哈希元数据保留到会话族自然过期。旧 Token 重放撤销整个会话族，包括其 Access Token；前端必须串行刷新，不能多标签同时刷新。
注销写入 JTI 黑名单至过期，删除当前会话族；改密和重置在数据库行锁下递增 auth_version，所有已有 Access/Refresh 在下一次使用时失效，Redis 中旧元数据按 TTL 自动回收。
每个请求从数据库读取用户状态与角色，开通创作者后原 JWT 即时生效。Redis 不可用时认证失败关闭，不绕过验证。
验证码 HMAC 安全值保存在 Redis，5分钟过期、60秒冷却、每邮箱每小时5次、每IP每小时20次、最多5次错误尝试。登录每邮箱5分钟10次、每IP5分钟50次。重置凭证15分钟、随机32字节、GETDEL 一次消费。
Redis 一次性凭证在 SQL 提交之前消费；若之后发生数据库故障，需重新申请凭证，保证不会因重试重复使用。

## 素材与交易（2026-10-18 新增）

统一 Gateway `http://localhost:28080`；金额为整数分，费率为 BPS。分页 `page=1&size=20`（size ≤100）。JSON DTO 由服务 OpenAPI 自动生成，开发环境设置 API_DOCS_ENABLED=true 后从服务 `/v3/api-docs` 导出。

| API | 权限 | 业务 |
|---|---|---|
| POST /materials | CREATOR | Draft：videoId,title,description,category,tags,priceCent,license |
| GET/PATCH /materials/{id} | 所有者 | 查看/生成新素材版本；已上架先下架 |
| GET /creators/me/materials | CREATOR | 本人的素材列表 |
| POST /materials/{id}/publish | 所有者 | `{idempotencyKey}` 创建上架费订单 |
| POST /materials/{id}/off-shelf | 所有者 | 下架 |
| GET /marketplace/materials[/{id}] | 公开 | 已发布素材，不返回原始对象 Key |
| GET /marketplace/materials/{id}/preview | 公开 | 水印 HLS 的短期预览地址 |
| GET /marketplace/fee-rule | 公开 | 当前收费版本 |
| POST /orders | 登录 | materialId,materialVersion,licenseId,acceptLicense=true,idempotencyKey |
| GET /orders[/{id}] | 本人 | 订单及固化价格/费用/协议 |
| POST /orders/{id}/pay | 本人 | 幂等生成统一支付单/渠道支付参数 |
| POST /orders/{id}/cancel | 本人 | 渠道查单并关单，已支付不能取消 |
| GET /payments/{id} | 本人 | 主动查单及恢复渠道成功状态 |
| POST /orders/{id}/refund | 本人 | `{idempotencyKey,reason}` 全额退款 |
| POST /dev/payments/{id}/succeed | 本人且 local/test+MOCK | 模拟支付成功，非真实资金 |
| POST /payment-notifications/wechat | 验签 | 微信支付及退款通知，不接受未签名通知 |
| GET /admin/orders | ADMIN | 所有订单分页 |
| POST /admin/orders/{id}/refund | ADMIN | 管理员全额退款 |
| POST /admin/fee-rules | ADMIN | 新费率版本及 effectiveAt，不覆盖旧规则 |
| POST /admin/materials/{id}/reject | ADMIN | 审核拒绝并停止公开销售 |

license 字段：commercialUse、scope、durationDays（0 为不限制期限）、redistribution、modification、description。订单固化完整协议以及买家确认时间。历史素材与协议版本不可覆盖。

支付单和业务订单分表；Mock 渠道状态独立提交且落库，业务服务崩溃后可查询恢复。暂只支持全额退款；未知 amount 等请求字段被拒绝。订单每用户最多 5 笔待支付，每日主动取消最多 10 次，超时默认 15 分钟。幂等键复用但内容不同返回 409。

## 权益、收益、提现及对账

| API | 权限 | 请求 / 说明 |
|---|---|---|
| GET /entitlements | 本人 | 已购买权益、协议及状态，page/size |
| POST /entitlements/{id}/download | 本人 | 重新验证权益、有效期和已支付订单，返回 2 分钟私有下载 URL |
| GET /earnings/balance | CREATOR | availableCent、frozenCent、payoutPendingCent、debtCent |
| GET /earnings/entries | CREATOR | 只读不可变流水，page/size |
| GET/PUT /withdrawals/account | CREATOR | PUT `{displayName,providerAccount}`；提现单保存账号快照 |
| POST /withdrawals | CREATOR | `{amountCent,idempotencyKey}`；锁定余额并提交渠道 |
| GET /withdrawals[/{id}] | 本人 | 列表 / 查询并恢复渠道状态 |
| POST /dev/withdrawals/{id}/complete | 本人且 local/test+MOCK | `{success:true}` 或 `{success:false}` |
| GET /admin/ledger/verify | ADMIN | 分录合计和账户汇总一致性报告，无改余额功能 |
| POST /admin/reconciliation/run | ADMIN | `{day:"2026-10-18"}` 从配置渠道获取账单 |
| POST /admin/reconciliation/fixture | ADMIN | `{day,rows}` 导入测试账单线索，不能直接标记付款成功 |
| GET /admin/reconciliation/batches | ADMIN | 批次分页 |
| GET /admin/reconciliation/batches/{id}/differences | ADMIN | 差异明细 |
| POST /admin/reconciliation/differences/{id}/acknowledge | ADMIN | `{resolution:"处理说明"}` 保存审计，不篡改流水 |

测试账单 rows 项：type（PAYMENT/REFUND）、id、channelId、amountCent、status、channelFeeCent（可空）。真实渠道状态修复需主动查单确认，金额差异与缺失记录保留待人工处理。定时任务使用 Asia/Shanghai，在每天 10:30 获取前一天账单。

资产支持 `/assets` 与 `/api/v1/videos` 两个前缀，上传 DTO 与完整接口见 VIDEO.md；DELETE /assets/uploads/{id} 为取消上传别名。

## 交易 OpenAPI 导出

`docs/commerce-openapi.json` 从实际 Spring Boot + Springdoc 上下文导出，包含上传、素材、订单、授权、结算和管理接口。生产默认 `API_DOCS_ENABLED=false`，Gateway 不转发文档路径，Compose 不需要为文档暴露 8081/8082。

```powershell
mvn -pl video-asset-service '-Dtest=CommerceIntegrationTest#openApiDocumentsBusinessRoutes' '-Dframehub.export-openapi=D:/FrameHub/docs/commerce-openapi.json' test
```

更换工作目录时相应调整绝对导出路径。测试依赖 Docker，用测试库生成，不连接应用业务库。导出上下文为 test，包含开发 Mock 路径；生产没有这些模拟操作入口。真实调用时服务器地址选择 http://localhost:28080，按接口权限携带 Bearer。
