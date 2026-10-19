package com.framehub.video.service;

import com.framehub.video.common.ApiException;
import com.framehub.video.config.VideoProperties;
import com.framehub.video.dto.UploadRequests.*;
import com.framehub.video.entity.UploadSession;
import com.framehub.video.media.MediaEngine;
import com.framehub.video.security.Identity;
import com.framehub.video.storage.ObjectStorage;
import com.framehub.video.vo.VideoViews;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.model.*;

@Service
public class UploadService {
  private final UploadTransactions tx;
  private final ObjectStorage storage;
  private final MediaEngine media;
  private final VideoProperties p;

  public UploadService(
      UploadTransactions tx, ObjectStorage storage, MediaEngine media, VideoProperties p) {
    this.tx = tx;
    this.storage = storage;
    this.media = media;
    this.p = p;
  }

  public VideoViews.Upload init(Identity user, Init req) {
    return view(tx.init(user, req), List.of());
  }

  public ObjectStorage.SignedPart presign(long user, String id, Presign req) {
    var s = tx.owned(user, id);
    active(s);
    if (req.partNumber() > s.partCount) throw new ApiException(400, "分片编号超出范围");
    long expected = expected(s, req.partNumber());
    if (req.sizeBytes() != expected) throw new ApiException(400, "预计分片大小不符");
    Duration ttl = Duration.between(Instant.now(), s.expiresAt);
    if (ttl.compareTo(p.presignTtl()) > 0) ttl = p.presignTtl();
    if (ttl.toSeconds() < 1) throw new ApiException(409, "会话已过期");
    return storage.presign(s.objectKey, s.uploadId, req.partNumber(), expected, ttl);
  }

  public VideoViews.Upload progress(long user, String id) {
    var s = tx.owned(user, id);
    List<Part> parts = List.of();
    if (Set.of("UPLOADING", "COMPLETING").contains(s.status))
      try {
        parts = storage.parts(s.objectKey, s.uploadId);
      } catch (S3Exception e) {
        if (!"NoSuchUpload".equals(e.awsErrorDetails().errorCode())) throw e;
      }
    return view(s, parts);
  }

  private VideoViews.Upload view(UploadSession s, List<Part> parts) {
    var uploaded =
        parts.stream()
            .map(x -> new VideoViews.UploadedPart(x.partNumber(), x.eTag(), x.size()))
            .toList();
    Set<Integer> present = new HashSet<>();
    parts.forEach(x -> present.add(x.partNumber()));
    List<Integer> missing = new ArrayList<>();
    if (!"COMPLETED".equals(s.status))
      for (int i = 1; i <= s.partCount; i++) if (!present.contains(i)) missing.add(i);
    return new VideoViews.Upload(
        s.sessionId,
        s.videoId,
        s.status,
        s.partBytes,
        s.partCount,
        s.declaredSize,
        s.expiresAt,
        uploaded,
        missing);
  }

  public VideoViews.Upload complete(long user, String id) throws Exception {
    var s = tx.prepare(user, id);
    if ("COMPLETED".equals(s.status)) return progress(user, id);
    Path dir = null;
    try {
      HeadObjectResponse object = storage.head(s.objectKey);
      if (object == null) {
        List<Part> parts = storage.parts(s.objectKey, s.uploadId);
        if (parts.size() < s.partCount) {
          tx.resumeParts(id);
          throw new MissingParts();
        }
        validateParts(s, parts);
        try {
          storage.complete(s.objectKey, s.uploadId, parts);
        } catch (S3Exception e) {
          if (storage.head(s.objectKey) == null) throw e;
        }
        object = storage.head(s.objectKey);
      }
      if (object == null
          || object.contentLength() != s.declaredSize
          || object.contentLength() > p.maxFileBytes()
          || !s.sessionId.equals(object.metadata().get("session-id")))
        throw new ApiException(400, "合并对象大小或归属不符");
      dir = media.workspace("probe-");
      Path source = dir.resolve("source");
      Instant deadline = Instant.now().plus(p.ioTimeout());
      storage.download(s.objectKey, source, p.maxFileBytes(), deadline);
      MediaEngine.Metadata metadata = media.probe(source, dir);
      tx.finish(id, metadata);
      return progress(user, id);
    } catch (MissingParts e) {
      throw e;
    } catch (ApiException | MediaEngine.InvalidMedia e) {
      abortInvalid(user, id, "源文件校验失败");
      throw new ApiException(400, "视频文件或分片校验失败");
    } finally {
      if (dir != null) media.cleanup(dir);
    }
  }

  public static class MissingParts extends ApiException {
    public MissingParts() {
      super(409, "分片尚未上传完整，可以继续上传");
    }
  }

  public void validateParts(UploadSession s, List<Part> parts) {
    if (parts.size() != s.partCount) throw new ApiException(400, "分片数量不完整");
    long total = 0;
    for (int i = 0; i < parts.size(); i++) {
      Part part = parts.get(i);
      if (part.partNumber() != i + 1
          || part.eTag() == null
          || part.eTag().isBlank()
          || part.size() != expected(s, i + 1)) throw new ApiException(400, "分片编号、ETag或实际大小不符");
      total = Math.addExact(total, part.size());
    }
    if (total != s.declaredSize || total > p.maxFileBytes())
      throw new ApiException(400, "实际上传大小超限");
  }

  private long expected(UploadSession s, int n) {
    return n == s.partCount ? s.declaredSize - (s.partCount - 1) * s.partBytes : s.partBytes;
  }

  private void active(UploadSession s) {
    if (!"UPLOADING".equals(s.status) || !s.expiresAt.isAfter(Instant.now()))
      throw new ApiException(409, "会话不可上传或已过期");
  }

  public void abort(long user, String id) {
    var s = tx.prepareAbort(user, id);
    if (Set.of("ABORTED", "FAILED").contains(s.status)) return;
    storage.abort(s.objectKey, s.uploadId);
    storage.delete(s.objectKey);
    tx.abortFinished(id, null);
  }

  private void abortInvalid(long user, String id, String reason) {
    var s = tx.prepareAbort(user, id);
    if (Set.of("ABORTED", "FAILED").contains(s.status)) return;
    storage.abort(s.objectKey, s.uploadId);
    storage.delete(s.objectKey);
    tx.abortFinished(id, reason);
  }
}
