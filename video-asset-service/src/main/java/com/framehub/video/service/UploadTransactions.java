package com.framehub.video.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.framehub.video.common.ApiException;
import com.framehub.video.config.VideoProperties;
import com.framehub.video.dto.UploadRequests.Init;
import com.framehub.video.entity.*;
import com.framehub.video.mapper.VideoMapper;
import com.framehub.video.media.MediaEngine;
import com.framehub.video.security.Identity;
import com.framehub.video.storage.ObjectStorage;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UploadTransactions {
  private final VideoMapper mapper;
  private final ObjectStorage storage;
  private final VideoProperties p;
  private final ObjectMapper json;

  public UploadTransactions(
      VideoMapper mapper, ObjectStorage storage, VideoProperties p, ObjectMapper json) {
    this.mapper = mapper;
    this.storage = storage;
    this.p = p;
    this.json = json;
  }

  @Transactional
  public UploadSession init(Identity user, Init req) {
    if (req.fileSize() > p.maxFileBytes()) throw new ApiException(400, "文件超过单文件大小限制");
    if (req.title().isBlank()
        || req.title().chars().anyMatch(c -> Character.isISOControl(c) || c == '<' || c == '>'))
      throw new ApiException(400, "标题不合法");
    mapper.ensureQuota(user.id());
    var quota = mapper.quota(user.id());
    if (req.fileSize() > p.quotaBytes() - quota.usedBytes - quota.reservedBytes)
      throw new ApiException(409, "存储配额不足");
    if (quota.activeUploads >= p.maxUploads()) throw new ApiException(429, "并发上传数已达上限");
    UploadSession s = new UploadSession();
    s.sessionId = UUID.randomUUID().toString();
    s.videoId = UUID.randomUUID().toString();
    s.userId = user.id();
    s.objectKey = "sources/" + user.userId() + "/" + s.videoId + "/original";
    s.declaredSize = req.fileSize();
    s.partBytes = p.partBytes();
    s.partCount = (int) ((req.fileSize() + p.partBytes() - 1) / p.partBytes());
    if (s.partCount > 10000) throw new ApiException(400, "分片数量过多");
    s.contentType = req.contentType();
    s.status = "UPLOADING";
    s.expiresAt = Instant.now().plus(p.sessionTtl());
    VideoAsset v = new VideoAsset();
    v.videoId = s.videoId;
    v.userId = s.userId;
    v.creatorId = user.creatorId();
    v.title = req.title().strip();
    v.sourceKey = s.objectKey;
    v.sourceSize = s.declaredSize;
    s.uploadId = storage.create(s.objectKey, s.contentType, s.sessionId);
    try {
      mapper.insertVideo(v);
      mapper.insertUpload(s);
      mapper.reserve(s.userId, s.declaredSize);
    } catch (RuntimeException e) {
      storage.abort(s.objectKey, s.uploadId);
      throw e;
    }
    // A crash or COMMIT failure after S3 creation is handled by orphan multipart reconciliation.
    return s;
  }

  public UploadSession owned(long user, String id) {
    UploadSession s = mapper.session(id);
    if (s == null) throw new ApiException(404, "上传会话不存在");
    if (s.userId != user) throw new ApiException(403, "无权访问此上传会话");
    return s;
  }

  @Transactional
  public UploadSession prepare(long user, String id) {
    UploadSession s = mapper.lockSession(id);
    if (s == null) throw new ApiException(404, "上传会话不存在");
    if (s.userId != user) throw new ApiException(403, "无权访问此上传会话");
    if ("COMPLETED".equals(s.status)) return s;
    if (!Set.of("UPLOADING", "COMPLETING").contains(s.status))
      throw new ApiException(409, "会话不可确认");
    if ("UPLOADING".equals(s.status) && s.expiresAt.isBefore(Instant.now()))
      throw new ApiException(409, "上传会话已过期");
    mapper.sessionStatus(id, "COMPLETING");
    s.status = "COMPLETING";
    return s;
  }

  @Transactional
  public void resumeParts(String id) {
    UploadSession s = mapper.lockSession(id);
    if ("COMPLETING".equals(s.status)) mapper.sessionStatus(id, "UPLOADING");
  }

  @Transactional
  public void finish(String id, MediaEngine.Metadata media) {
    UploadSession s = mapper.lockSession(id);
    if ("COMPLETED".equals(s.status)) return;
    if (!"COMPLETING".equals(s.status)) throw new ApiException(409, "上传状态已变化");
    mapper.quota(s.userId);
    VideoAsset v = mapper.video(s.videoId);
    v.durationSeconds = media.duration();
    v.width = media.width();
    v.height = media.height();
    v.fps = media.fps();
    v.bitrate = media.bitrate();
    v.formatName = media.format();
    mapper.uploaded(v);
    String event = UUID.randomUUID().toString();
    try {
      mapper.outbox(
          event,
          s.videoId,
          s.userId,
          json.writeValueAsString(
              Map.of("videoId", s.videoId, "userId", "" + s.userId, "sourceKey", s.objectKey)));
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
    mapper.release(s.userId, s.declaredSize, s.declaredSize);
    mapper.sessionStatus(id, "COMPLETED");
  }

  @Transactional
  public UploadSession prepareAbort(long user, String id) {
    UploadSession s = mapper.lockSession(id);
    if (s == null) throw new ApiException(404, "上传会话不存在");
    if (s.userId != user) throw new ApiException(403, "无权取消此会话");
    if ("COMPLETED".equals(s.status)) throw new ApiException(409, "已完成上传不可取消");
    if ("ABORTED".equals(s.status) || "FAILED".equals(s.status)) return s;
    mapper.sessionStatus(id, "ABORTING");
    s.status = "ABORTING";
    return s;
  }

  @Transactional
  public void abortFinished(String id, String reason) {
    UploadSession s = mapper.lockSession(id);
    if (Set.of("ABORTED", "FAILED", "COMPLETED").contains(s.status)) return;
    mapper.quota(s.userId);
    mapper.release(s.userId, s.declaredSize, 0);
    mapper.sessionStatus(id, reason == null ? "ABORTED" : "FAILED");
    mapper.status(s.videoId, reason == null ? "ABORTED" : "FAILED", reason);
  }
}
