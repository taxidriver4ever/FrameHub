package com.framehub.video.entity;

public class TranscodeStep {
  public String id,
      taskId,
      stageName,
      status,
      idempotencyKey,
      artifactPrefix,
      manifestKey,
      attemptId,
      configVersion,
      sourceVersion,
      resultJson,
      lastError;
  public long executionVersion;
  public int retryCount;
}
