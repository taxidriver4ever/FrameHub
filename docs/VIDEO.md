# 视频资产模块

## 运行

在已有 `.env` 中增加 `.env.example` 的 VIDEO_DB_PASSWORD、CANAL_PASSWORD（随机密码；Canal 密码最多 32 字符，初始化脚本生成 24 字节随机数的 Base64）、VIDEO_DB_USERNAME 和 VIDEO_DB_URL。新项目运行 `scripts/init-local.ps1` 可一并生成。不要覆盖现有数据库密码。

```powershell
docker compose -f compose.yml -f compose.local.yml up -d --build
python scripts/video-smoke.py
```

本地 Java 启动：导入 `.env`，设置 FFMPEG_PATH/FFPROBE_PATH（或加入 PATH），启动 8081 user-service、8082 video-asset-service、28080 gateway-service。基础设施使用 Compose。Kafka 本地地址 localhost:19092，容器地址 kafka:9092。VIDEO_CONSUMER_ENABLED=false 可将 HTTP 接口和消费者分别部署同一应用，无需新增服务或调度器。

生产使用 `compose.yml`，不加载 local override。仅 Gateway 发布端口；user-service、video-asset-service、MySQL、Redis、Kafka、Canal 均无宿主机端口。MINIO_ENDPOINT 必须配置为浏览器可达的 HTTPS 对象存储入口（生产通过独立反向代理暴露 MinIO S3 API，桶保持私有），MINIO_INTERNAL_ENDPOINT 为容器地址。ALLOWED_ORIGINS 为精确前端 Origin 列表，禁止带凭证的通配 Origin。生产 TLS 和对象存储入口的域名由部署环境提供。

## API 与身份

全部视频接口经 `http://localhost:28080/api/v1/videos`，需要 Bearer JWT 和已激活 CREATOR。视频服务将 JWT 交给 user-service 的内网 `/internal/auth/identity` 验证，复用 JWT、撤销状态与用户角色；Gateway 不重复认证。该内网地址没有 Gateway 路由。资产管理仅所属创作者可读；公开素材走 /marketplace，购买授权和短期原片下载走 /entitlements。支持 /assets 与 /api/v1/videos 兼容前缀，DELETE /assets/uploads/{id} 取消上传。

统一响应：`{"code":0,"message":"success","data":...}`（实际 message 以 API 返回为准）。ID 均为字符串，视频/上传/事件采用 UUID，用户 ID 沿用用户中心 Snowflake。

| 方法 | 路径（相对 /api/v1/videos） | 请求/说明 |
|---|---|---|
| POST | /uploads/init | `{"filename":"demo.mp4","title":"Demo","fileSize":10485760,"contentType":"video/mp4"}` |
| POST | /uploads/{id}/parts/presign | `{"partNumber":1,"sizeBytes":10485760}` |
| GET | /uploads/{id} | 返回真实 uploadedParts（partNumber、etag、size）及 missingParts |
| POST | /uploads/{id}/complete | 无需前端提交 ETag；服务端 ListParts 校验与合并 |
| POST | /uploads/{id}/abort | 幂等取消并释放预留配额 |
| GET | /{videoId} | 状态、时长、分辨率、码率、封面及清晰度 |
| GET | /me?page=1&size=20&status=READY | 按创建时间倒序、所有者隔离 |
| GET | /{videoId}/playback | masterUrl、resolutions、expiresIn |

初始化 data 示例：
```json
{"uploadSessionId":"uuid","videoId":"uuid","status":"UPLOADING","partSize":16777216,"partCount":1,"fileSize":10485760,"expiresAt":"...","uploadedParts":[],"missingParts":[1]}
```
预签名 data：`{"uploadUrl":"https://...","requiredHeaders":{"host":["..."]},"expiresIn":900}`。浏览器直接 PUT Blob 到 MinIO，浏览器自行设置 Host/Content-Length，不将文件发送到 Java Controller。刷新页面后 GET 会话查询实际分片；对缺失分片重新签发 URL。Content-Length 的实测结果见 VIDEO-VALIDATION.md，不以客户端声明作为容量保障。

预览资源使用私有桶：动态重写 master/media playlist 为短时预签名 URL，TS 和封面亦单独签名。没有公开桶策略。签名 URL 是临时 bearer 能力，过期后重新请求 playback。

## 数据库与阶段

独立库 framehub_video；V1：user_storage、video_assets、upload_sessions、outbox_event、failed_cdc_events。V2：video_transcode_task、video_transcode_step，整体处理状态 PROCESSING，唯一约束 task+stage 和 video/event。

上传确认先持久化 COMPLETING，再执行 MinIO 合并、实际大小/归属校验和 FFprobe。COMPLETED、视频 UPLOADED、配额转正及唯一 outbox 行在同一事务提交。合并成功而事务失败时，重试根据源对象/session-id 恢复。分片尚未齐全返回 409 并恢复 UPLOADING。实际大小错误或无效媒体会 Abort/清理并释放预留配额。

正常路径：MySQL ROW Binlog → Canal Kafka MQ（flat JSON）→ video.outbox.cdc 三分区 → Consumer 同事务创建 Inbox 和 PENDING 任务 → ACK。独立 Worker 扫描到期任务，通过 CAS 领取并续约 Lease/Heartbeat；FFmpeg → MinIO 校验 → 同事务 READY 与 ASSET_READY Outbox。execution_version 作为 fencing token 阻止旧 Worker 提交。应用不轮询 Outbox 发 Kafka，媒体和交易使用独立消费组。

阶段顺序：PROBE → 按源分辨率选择 TRANSCODE_480P/720P/1080P（小源视频使用实际偶数高度）→ COVER → MASTER_PLAYLIST → VERIFY → PUBLISH。不放大原视频。HLS 包含配置水印。

逻辑幂等键为 SHA-256(videoId|sourceVersion|stageName|configVersion)。sourceVersion 使用源 key、ETag 和真实 size；configVersion 包含配置版本、清晰度、水印和编码方案。修改编码参数时必须更新 VIDEO_CONFIG_VERSION。

物理路径 `processed/{videoId}/{sourceVersion}/{attemptId}/{stageName}-{uuid}/`。开始阶段前持久化目标 prefix、manifestKey 和 executionVersion；所有文件上传成功后最后写 manifest。Manifest 保存视频、阶段、尝试、源/配置版本、产物数量、名称、大小、ETag、SHA-256 和依赖。复用前 HEAD 校验大小/ETag/SHA-256 元数据，并核验 HLS 的每个切片引用；不能仅凭 manifest 存在或 SUCCEEDED 复用。

V3 增加统一领域 Outbox、event_inbox、任务 Lease/Heartbeat/next_retry_at；V4/V5 为交易及结算表。

完整产物而 DB Checkpoint 失败：读取原 prefix，验证后补写成功，不重复 FFmpeg。缺失/损坏文件：只重做该阶段，使用新 prefix。旧执行无法提交 Checkpoint 或最终发布；PUBLISH 在事务中检查所有必需阶段及 executionVersion。READY 重投先验证产物再 ACK。

## 超时、重试与清理

默认 2GiB 文件、30 分钟时长、5GiB 原片+预留配额、3 个上传会话、16MiB 分片、24h 会话、15m URL；全部在 application.yml 中通过环境变量配置。用户并发转码最多 2，通过 MySQL advisory lock 限制；Worker 使用有界线程池，消费者默认 3 个。

Worker 的处理预算独立于 Kafka Poll；后台有界线程池领取持久任务，过期 Lease 可由另一实例接管，普通失败进入 RETRY_WAIT 并指数退避。失去 Lease 中断当前工作，最终发布仍须校验版本。FFmpeg 子进程设置 deadline、强制终止并等待退出，限制临时磁盘预算；Compose 限制 3 CPU、4GiB 内存和 256 PID。

上传清理器处理过期会话、COMPLETING 补偿、孤儿产物和临时播放列表；Worker 调度器负责媒体任务，不依赖重新投递 Kafka 才能恢复。有效任务/Checkpoint 引用的目录受保护。产物元数据校验通过后才复用；缺失阶段以新物理目录重建。

## Canal 运维

MySQL 8.4 启用 ROW、FULL row image、server-id=1、7 天 Binlog 保留；Canal 1.1.8 使用唯一 slaveId=12345 和独立复制账号。初始化脚本对已有 MySQL 数据卷同样生效。Canal 只订阅 framehub_video.outbox_event，按 aggregate_id 分配到三分区。首次启动使用初始化任务记录的最早保留 Binlog，从 position=4 重放，避免服务就绪早于 CDC 而漏过事件；历史事件由消费者幂等处理。Canal conf/video（包含文件位点与 TSDB）挂持久卷，启动脚本保留 metadata，不自动重置到最新位点。Kafka 持久卷保留日志。

若停机超过 Binlog 保留期，必须先恢复所需日志或人工审核历史 Outbox 后补发，禁止静默跳过缺失事件。备份数据库时同步备份 Canal 位点；本地单节点 Kafka replication-factor=1 是开发部署，生产可保持应用协议并调整集群副本数，不宣称单节点具备高可用。

## 实现位置

- `video-asset-service/.../service/UploadTransactions.java`、`UploadService.java`：上传/配额/原子 Outbox。
- `.../cdc/`：Canal envelope、多行解析、手动 ACK、失败隔离、并发限制。
- `.../service/TranscodePipeline.java`、`CheckpointTransactions.java`：阶段执行、恢复、发布保护。
- `.../storage/StageArtifacts.java`：manifest、实际对象和引用校验。
- `.../media/`：FFprobe、FFmpeg、进程预算与终止。
- `deploy/`、`compose*.yml`、`Dockerfile`：环境与运行依赖。
- `CheckpointIntegrationTest`、`MediaEngineTest`、`scripts/video-smoke.py`：恢复故障注入、实际转码和端到端测试。

协议参考：[Canal 官方 Kafka MQ 配置](https://github.com/alibaba/canal/wiki/Canal-Kafka-RocketMQ-QuickStart)、[Spring Kafka 错误处理](https://docs.spring.io/spring-kafka/reference/kafka/annotation-error-handling.html)。
