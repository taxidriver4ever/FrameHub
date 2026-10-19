package com.framehub.user.profile.service;

import com.framehub.user.common.*;
import com.framehub.user.config.AppProperties;
import com.framehub.user.profile.dto.ProfileRequests.Upload;
import com.framehub.user.security.RedisState;
import io.minio.*;
import io.minio.http.Method;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class AvatarService {
  public record UploadView(String objectKey, String uploadUrl, long expiresIn) {}

  private static final Map<String, String> TYPES =
      Map.of("image/png", "png", "image/jpeg", "jpg", "image/webp", "webp");
  private final MinioClient minio, signing;
  private final AppProperties p;
  private final RedisState state;

  public AvatarService(
      MinioClient minio,
      @org.springframework.beans.factory.annotation.Qualifier("signingMinio") MinioClient signing,
      AppProperties p,
      RedisState state) {
    this.minio = minio;
    this.signing = signing;
    this.p = p;
    this.state = state;
  }

  public UploadView upload(long userId, Upload req) throws Exception {
    if (!TYPES.containsKey(req.contentType()) || req.sizeBytes() < 1 || req.sizeBytes() > 2097152)
      throw new BusinessException(400, "仅支持2MB以内JPEG、PNG、WebP");
    String key = "avatars/" + userId + "/" + UUID.randomUUID() + "." + TYPES.get(req.contentType());
    state.put("avatar:" + key, userId + ":" + req.contentType() + ":" + req.sizeBytes(), 600);
    String url =
        signing.getPresignedObjectUrl(
            GetPresignedObjectUrlArgs.builder()
                .method(Method.PUT)
                .bucket(p.minioBucket())
                .object(key)
                .expiry(300)
                .build());
    return new UploadView(key, url, 300);
  }

  public String confirm(long userId, String key) throws Exception {
    if (!key.startsWith("avatars/" + userId + "/")) throw new BusinessException(403, "对象不属于当前用户");
    String expected = state.get("avatar:" + key);
    if (expected == null) throw new BusinessException(400, "上传凭证无效或已过期");
    StatObjectResponse stat;
    try {
      stat = minio.statObject(StatObjectArgs.builder().bucket(p.minioBucket()).object(key).build());
    } catch (io.minio.errors.ErrorResponseException e) {
      throw new BusinessException(400, "上传对象不存在");
    }
    if (!expected.equals(userId + ":" + stat.contentType() + ":" + stat.size())
        || stat.size() < 1
        || stat.size() > 2097152
        || !TYPES.containsKey(stat.contentType())) throw new BusinessException(400, "对象大小或类型不符");
    // Inspect only the signature; file uploads remain direct browser-to-MinIO.
    try (var in =
        minio.getObject(
            GetObjectArgs.builder()
                .bucket(p.minioBucket())
                .object(key)
                .matchETag(stat.etag())
                .offset(0L)
                .length(12L)
                .build())) {
      byte[] header = in.readNBytes(12);
      if (!signature(header, stat.contentType())) throw new BusinessException(400, "文件内容与类型不符");
    }
    // A private final copy prevents reuse of the still-valid PUT URL from replacing an approved
    // avatar.
    String finalKey =
        "avatars/"
            + userId
            + "/confirmed/"
            + UUID.randomUUID()
            + "."
            + TYPES.get(stat.contentType());
    minio.copyObject(
        CopyObjectArgs.builder()
            .bucket(p.minioBucket())
            .object(finalKey)
            .source(
                CopySource.builder()
                    .bucket(p.minioBucket())
                    .object(key)
                    .matchETag(stat.etag())
                    .build())
            .build());
    state.delete("avatar:" + key);
    return finalKey;
  }

  static boolean signature(byte[] b, String type) {
    if (b.length < 12) return false;
    return switch (type) {
      case "image/png" ->
          Arrays.equals(Arrays.copyOf(b, 8), new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10});
      case "image/jpeg" -> (b[0] & 255) == 255 && (b[1] & 255) == 216 && (b[2] & 255) == 255;
      case "image/webp" ->
          new String(b, 0, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("RIFF")
              && new String(b, 8, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("WEBP");
      default -> false;
    };
  }

  public String url(String key) {
    if (key == null) return null;
    try {
      return signing.getPresignedObjectUrl(
          GetPresignedObjectUrlArgs.builder()
              .method(Method.GET)
              .bucket(p.minioBucket())
              .object(key)
              .expiry(300)
              .build());
    } catch (Exception e) {
      throw new IllegalStateException("Cannot sign avatar URL");
    }
  }
}
