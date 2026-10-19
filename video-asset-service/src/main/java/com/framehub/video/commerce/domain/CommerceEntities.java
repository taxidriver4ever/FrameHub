package com.framehub.video.commerce.domain;

import java.time.*;

public final class CommerceEntities {
  private CommerceEntities() {}

  public static class FeeRule {
    public String id;
    public int version, commissionBps, channelFeeBps;
    public long listingFeeCent, minimumWithdrawalCent, freezeSeconds, createdBy;
    public Instant effectiveAt, createdAt;
  }

  public static class Material {
    public String id, videoId, status;
    public long ownerId;
    public int currentVersion;
    public Instant createdAt, updatedAt;
  }

  public static class MaterialVersion {
    public String materialId,
        title,
        description,
        category,
        tags,
        licenseId,
        sourceKey,
        sourceVersion;
    public int version;
    public long priceCent;
    public Instant createdAt;
  }

  public static class License {
    public String id, materialId, terms;
    public int version;
    public Instant createdAt;
  }

  public static class Order {
    public String id,
        orderType,
        materialId,
        status,
        idempotencyKey,
        requestHash,
        feeSnapshot,
        licenseSnapshot,
        cancelReason;
    public long userId, creatorId, amountCent, commissionCent, channelFeeCent, creatorCent;
    public int materialVersion, ruleVersion;
    public Instant licenseConfirmedAt, expiresAt, paidAt, createdAt, updatedAt;
  }

  public static class Payment {
    public String id, orderId, provider, status, currency, channelId, checkoutUrl, lastError;
    public long amountCent;
    public Long channelFeeActualCent;
    public Instant paidAt, createdAt, updatedAt;
  }

  public static class Refund {
    public String id, orderId, paymentId, idempotencyKey, status, channelId, reason, lastError;
    public long requestedBy, amountCent;
    public Instant createdAt, updatedAt;
  }

  public static class Entitlement {
    public String id, orderId, materialId, status, licenseSnapshot, sourceKey, revokeReason;
    public long userId;
    public int materialVersion;
    public Instant expiresAt, revokedAt, createdAt;
  }

  public static class UserGuard {
    public long userId;
    public LocalDate cancelDay;
    public int cancelCount;
  }
}
