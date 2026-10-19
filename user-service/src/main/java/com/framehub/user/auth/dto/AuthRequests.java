package com.framehub.user.auth.dto;

import jakarta.validation.constraints.*;

public final class AuthRequests {
  private AuthRequests() {}

  public record Register(
      @NotBlank @Email @Size(max = 254) String email,
      @NotBlank @Size(min = 10, max = 72) String password,
      @NotBlank @Pattern(regexp = "[0-9]{6}") String verificationCode,
      @NotBlank @Size(max = 64) String nickname) {}

  public record Login(
      @NotBlank @Email @Size(max = 254) String email, @NotBlank @Size(max = 72) String password) {}

  public record EmailCode(
      @NotBlank @Email @Size(max = 254) String email,
      @NotNull @Pattern(regexp = "REGISTER") String scene) {}

  public record Forgot(@NotBlank @Email @Size(max = 254) String email) {}

  public record Reset(
      @NotBlank @Size(max = 128) String resetToken,
      @NotBlank @Size(min = 10, max = 72) String newPassword) {}
}
