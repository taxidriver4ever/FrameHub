package com.framehub.video.config;

import jakarta.validation.constraints.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("video")
public record VideoProperties(
    @NotBlank String identityUrl,
    @NotBlank String bucket,
    @NotBlank String endpoint,
    @NotBlank String publicEndpoint,
    @NotBlank String accessKey,
    @NotBlank String secretKey,
    @Min(1) long maxFileBytes,
    @Min(5242880) long partBytes,
    @Min(1) long quotaBytes,
    @Min(1) int maxUploads,
    @Min(1) int consumerConcurrency,
    @Min(1) int perUserConcurrency,
    @Min(1) int ffmpegConcurrency,
    @NotNull Duration sessionTtl,
    @NotNull Duration presignTtl,
    @NotNull Duration probeTimeout,
    @NotNull Duration ffmpegTimeout,
    @NotNull Duration ioTimeout,
    @NotNull Duration jobTimeout,
    @Min(1) long maxDurationSeconds,
    @Min(1) long maxTempBytes,
    @Min(1) int maxDimension,
    @Min(1) int maxOutputFiles,
    @Min(1) int ffmpegThreads,
    @Min(1) int maxAttempts,
    @NotNull Duration retryBackoff,
    @NotNull Duration orphanGrace,
    @NotNull Path workDir,
    @NotBlank String ffmpeg,
    @NotBlank String ffprobe,
    @Pattern(regexp = "[A-Za-z0-9 _.-]{0,64}") String watermark,
    @NotBlank String configVersion,
    @NotEmpty List<Integer> renditions,
    @NotBlank String database,
    @NotBlank String topic,
    @NotBlank String group,
    boolean consumerEnabled,
    boolean cleanupEnabled,
    @Min(1) long pollIntervalMs) {
  public VideoProperties {
    if (orphanGrace != null
        && jobTimeout != null
        && ioTimeout != null
        && orphanGrace.compareTo(jobTimeout.plus(ioTimeout).plus(Duration.ofMinutes(5))) <= 0)
      throw new IllegalArgumentException(
          "Orphan grace must exceed maximum live execution duration");
    if (jobTimeout != null
        && ffmpegTimeout != null
        && ioTimeout != null
        && probeTimeout != null
        && jobTimeout.compareTo(ffmpegTimeout.plus(ioTimeout).plus(probeTimeout)) < 0)
      throw new IllegalArgumentException("Job timeout must cover media and I/O budgets");
  }
}
