# 视频模块验收记录（2026-10-17）

## 实际执行结果

- 用户服务 17 项、Gateway 6 项回归测试通过；Gateway 路由测试包含 `/api/v1/videos/uploads/init` 和带查询参数的视频列表路径。
- 最终视频测试：CheckpointIntegrationTest 19 项 + MediaEngineTest 2 项，21 项通过，0 失败、0 错误、0 跳过。合计三个模块 44 项。
- Checkpoint 集成测试使用真实 MySQL 8.4 和 MinIO；用故障注入控制媒体阶段/提交故障，验证恢复，不以真实杀进程冒充所有故障场景。MediaEngineTest 单独调用真实 FFprobe/FFmpeg。
- Docker Compose 构建三个应用成功；视频镜像包含 FFmpeg 和字体，使用非 root 用户。数据库 V1/V2 迁移真实执行成功。
- `scripts/video-smoke.py` 实测通过 Gateway 注册、邮件验证码、登录 Cookie、资料查询、创作者激活、MinIO 直接 PUT、查询真实进度、重复 complete、Canal CDC、Kafka、FFmpeg、私有 HLS/封面输出、HLS 下载与 FFmpeg 解码。
- 使用最终视频镜像，并发执行三个独立用户的完整测试均通过；两个任务的处理时间重叠（08:48:51–08:48:55 UTC），证明不同 Consumer 同时执行。第三个用户落入相同分区，随后顺序执行。三个任务均 READY、attempt=1，输出 480P/720P，没有对 720P 源生成 1080P。
- 实测源文件 2,431,986 字节、1280×720、3 秒。结果在 `video-smoke-1.json`、`video-smoke-2.json`、`video-smoke-3.json`。
- 从真实 Kafka 抓取 Canal INSERT JSON，保存为 `canal-envelope.sample.json`，确认 database/table/type/data[]，event_version 和 BIGINT user_id 为字符串。
- Canal 1.1.8 + MySQL 8.4 + Kafka 3.9.1 联通；修复复制注册密码超过 32 字符的问题。重启 Canal 后从持久位点 mysql-bin.000001:37986 恢复，未重置到最新日志。
- 已检查 Kafka 消费组提交位点：已处理分区 lag=0，三分区分配给三个 Consumer。
- 已检查生产 `compose.yml` 的解析结果：仅 Gateway 发布 127.0.0.1:8080；user-service、video-asset-service、MySQL、Redis、Kafka、Canal 均无 ports。

## Checkpoint 与上传覆盖

1. 480P/720P 成功、1080P 失败，只重新执行 1080P。
2. 媒体阶段中断，重新执行当前阶段。
3. 部分对象上传，无完整 manifest，不标记成功并使用新目录重试。
4. manifest 全部写完、Checkpoint 提交失败，保留原目录并复用，避免重复编码。
5. READY 重投检查实际产物与发布指针后跳过编码。
6. 源 ETag 变化使阶段失效。
7. configVersion 变化使整个不兼容流水线重新生成。
8. 旧版本不能提交 Checkpoint 或 PUBLISH；活跃任务拒绝接管，过期任务能恢复。
9. 必需阶段未成功时不能发布 READY。
10. 缺失 TS 和无效 manifest 被识别，受损阶段重新生成。
11. MinIO 合并后 DB 提交失败保持 COMPLETING；再次 complete 恢复，重复确认只有一条 Outbox。
12. 上传归属、HTTP 未登录/非创作者权限、文件上限、总配额和并发上传上限、缺片续传、幂等取消。
13. 终态失败与 failed_cdc_events 共同持久化；活跃任务不能被错误隔离。
14. 实际 FFprobe、HLS、封面、不放大源视频；真实 FFmpeg 超时终止并及时返回。

## Content-Length 实测

AWS SDK S3Presigner 2.31.78 + 当前 MinIO 固定镜像：签名头含 `[content-length, host]`。
声明 10 字节并上传 10 字节返回 200；同一约束上传 11 字节返回 **403**。

这只证明当前组合的行为。仍保留 URL 数量/过期限制、ListParts 的实际大小与累计字节数校验、合并对象大小和 session-id 校验，以及 Abort/过期清理。更换 SDK、MinIO 或代理后应重新执行该测试，不能把客户端 fileSize 声明当作存储容量保证。

## 重跑

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
# Docker Desktop 29 使用 docker-java 支持的 API，避免旧默认 API 被拒绝。
$env:JAVA_TOOL_OPTIONS='-Dapi.version=1.44'
mvn -B -ntp test
docker compose -f compose.yml -f compose.local.yml up -d --build
python scripts/video-smoke.py
```

运行集成测试需要 Docker；运行媒体测试和 smoke 需要本机 FFmpeg/FFprobe 在 PATH，或媒体测试设置 FFMPEG_PATH/FFPROBE_PATH。Testcontainers 在无 Docker 的开发环境会标为 skipped，不能将该结果视为验收通过。

## 测试边界

未执行 2GiB、30 分钟视频或持续高并发压力测试；当前真实端到端样例为短视频。同步消费者的 80 分钟记录预算、60 分钟累计编码预算和 110 分钟 poll 间隔已在配置/代码中约束，容量规划仍应以目标硬件压力测试为准。

本地 Kafka 为单节点，不能代表生产高可用；生产对象存储 HTTPS 域名、TLS 证书和外部网络边界需由实际部署提供。崩溃恢复主要通过持久状态与故障注入测试验证，未宣称完成所有时刻的操作系统级断电/网络分区测试。
