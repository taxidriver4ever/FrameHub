package com.framehub.video.dto;

import jakarta.validation.constraints.*;

public final class UploadRequests {
  private UploadRequests() {}

  public record Init(
      @NotBlank @Size(max = 255) String filename,
      @NotBlank @Size(max = 128) String title,
      @Min(1) long fileSize,
      @NotBlank @Pattern(regexp = "video/(mp4|webm|quicktime|x-matroska|x-msvideo)")
          String contentType) {}

  public record Presign(@Min(1) int partNumber, @Min(1) long sizeBytes) {}
}
