package com.framehub.video.commerce.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.framehub.video.commerce.domain.CommerceEntities.*;
import com.framehub.video.commerce.domain.JsonData;
import java.time.Instant;

public final class CommerceViews {
  public record MaterialView(
      String id,
      String creatorId,
      String videoId,
      int version,
      String status,
      String title,
      String description,
      String category,
      JsonNode tags,
      long priceCent,
      String licenseId,
      int licenseVersion,
      JsonNode license,
      Instant createdAt) {}

  public record OrderView(
      String id,
      String type,
      String materialId,
      int materialVersion,
      String status,
      long amountCent,
      JsonNode fees,
      JsonNode license,
      Instant licenseConfirmedAt,
      Instant expiresAt,
      Instant paidAt) {}

  public record PaymentView(
      String id,
      String orderId,
      String provider,
      String status,
      long amountCent,
      String currency,
      String checkoutUrl,
      Instant paidAt) {}

  public record RefundView(
      String id, String orderId, String status, long amountCent, String reason) {}

  public static OrderView order(Order o, JsonData json) {
    return new OrderView(
        o.id,
        o.orderType,
        o.materialId,
        o.materialVersion,
        o.status,
        o.amountCent,
        json.read(o.feeSnapshot),
        json.read(o.licenseSnapshot),
        o.licenseConfirmedAt,
        o.expiresAt,
        o.paidAt);
  }

  public static PaymentView payment(Payment p) {
    return new PaymentView(
        p.id, p.orderId, p.provider, p.status, p.amountCent, p.currency, p.checkoutUrl, p.paidAt);
  }

  public static RefundView refund(Refund r) {
    return new RefundView(r.id, r.orderId, r.status, r.amountCent, r.reason);
  }
}
