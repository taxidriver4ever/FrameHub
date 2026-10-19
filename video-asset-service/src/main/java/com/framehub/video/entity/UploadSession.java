package com.framehub.video.entity;

import java.time.Instant;

public class UploadSession {
  public String sessionId, videoId, objectKey, uploadId, contentType, status;
  public long userId, declaredSize, partBytes;
  public int partCount;
  public Instant expiresAt, createdAt, updatedAt;
}
