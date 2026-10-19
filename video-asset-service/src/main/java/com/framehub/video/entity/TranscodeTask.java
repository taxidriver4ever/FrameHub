package com.framehub.video.entity;

import java.time.Instant;

public class TranscodeTask {
  public String taskId,
      videoId,
      eventId,
      status,
      attemptId,
      lastError,
      sourceVersion,
      configVersion;
  public long executionVersion;
  public int attempt, retryCount;
  public Instant processingDeadline, leaseUntil, heartbeatAt, nextRetryAt;
  public String leaseOwner;
}
