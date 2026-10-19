package com.framehub.video.commerce.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

public final class CommerceRequests {
  public record LicenseTerms(
      boolean commercialUse,
      @NotBlank @Size(max = 500) String scope,
      @Min(0) @Max(36500) int durationDays,
      boolean redistribution,
      boolean modification,
      @NotBlank @Size(max = 5000) String description) {}

  public record Draft(
      @NotBlank String videoId,
      @NotBlank @Size(max = 128) String title,
      @NotNull @Size(max = 10000) String description,
      @NotBlank @Size(max = 64) String category,
      @NotNull @Size(max = 20) List<@NotBlank @Size(max = 40) String> tags,
      @Min(1) @Max(1000000000000L) long priceCent,
      @NotNull @Valid LicenseTerms license) {}

  public record Edit(
      @Size(min = 1, max = 128) String title,
      @Size(max = 10000) String description,
      @Size(min = 1, max = 64) String category,
      @Size(max = 20) List<@NotBlank @Size(max = 40) String> tags,
      @Min(1) @Max(1000000000000L) Long priceCent,
      @Valid LicenseTerms license) {}

  public record Idempotency(@NotBlank @Size(max = 100) String idempotencyKey) {}

  public record Purchase(
      @NotBlank String materialId,
      @Min(1) int materialVersion,
      @NotBlank String licenseId,
      @AssertTrue boolean acceptLicense,
      @NotBlank @Size(max = 100) String idempotencyKey) {}

  public record RefundRequest(
      @NotBlank @Size(max = 100) String idempotencyKey, @NotBlank @Size(max = 500) String reason) {}

  public record Rule(
      @Min(1) @Max(1000000000000L) long listingFeeCent,
      @Min(0) @Max(10000) int commissionBps,
      @Min(0) @Max(10000) int channelFeeBps,
      @Min(1) @Max(1000000000000L) long minimumWithdrawalCent,
      @Min(0) @Max(31536000) long freezeSeconds,
      @NotNull Instant effectiveAt) {}

  public record WithdrawalRequest(
      @Min(1) @Max(1000000000000L) long amountCent,
      @NotBlank @Size(max = 100) String idempotencyKey) {}

  public record WithdrawalAccount(
      @NotBlank @Size(max = 120) String displayName,
      @NotBlank @Size(max = 200) String providerAccount) {}

  public record MockOutcome(boolean success) {}
}
