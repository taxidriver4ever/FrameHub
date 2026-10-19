package com.framehub.user.profile.dto;

import jakarta.validation.constraints.*;

public final class ProfileRequests {
  private ProfileRequests() {}

  public record Password(
      @NotBlank @Size(max = 72) String currentPassword,
      @NotBlank @Size(min = 10, max = 72) String newPassword) {}

  public record Upload(
      @NotBlank @Size(max = 255) String filename,
      @NotBlank String contentType,
      @Min(1) @Max(2097152) long sizeBytes) {}

  public record Avatar(@NotBlank @Size(max = 255) String objectKey) {}
}
