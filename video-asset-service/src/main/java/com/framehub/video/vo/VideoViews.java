package com.framehub.video.vo;

import java.time.Instant;
import java.util.*;

public final class VideoViews {
  private VideoViews() {}

  public record UploadedPart(int partNumber, String etag, long size) {}

  public record Upload(
      String uploadSessionId,
      String videoId,
      String status,
      long partSize,
      int partCount,
      long fileSize,
      Instant expiresAt,
      List<UploadedPart> uploadedParts,
      List<Integer> missingParts) {}

  public record Video(
      String videoId,
      String userId,
      String creatorId,
      String title,
      String status,
      long originalSize,
      Double duration,
      Integer width,
      Integer height,
      Double fps,
      Long bitrate,
      String coverUrl,
      List<Integer> resolutions,
      Instant createdAt,
      Instant updatedAt) {}

  public record Page(List<Video> items, long total, int page, int size) {}

  public record Playback(String masterUrl, List<Integer> resolutions, long expiresIn) {}
}
