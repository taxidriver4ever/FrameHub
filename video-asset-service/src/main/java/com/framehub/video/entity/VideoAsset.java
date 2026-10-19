package com.framehub.video.entity;

import java.time.Instant;

public class VideoAsset {
  public String videoId,
      creatorId,
      title,
      sourceKey,
      status,
      attemptId,
      eventId,
      outputPrefix,
      coverKey,
      outputsJson,
      failureReason,
      formatName;
  public long userId, sourceSize, executionVersion;
  public int attempts;
  public Double durationSeconds, fps;
  public Long bitrate;
  public Integer width, height;
  public Instant createdAt, updatedAt;
}
