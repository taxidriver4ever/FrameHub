package com.framehub.video.commerce.provider;

import java.time.*;
import java.util.*;

public interface PaymentProvider {
  String name();

  record Request(
      String paymentId, long amountCent, String currency, String description, Instant expiresAt) {}

  record Result(
      String paymentId,
      String status,
      long amountCent,
      String currency,
      String channelId,
      String checkoutUrl,
      Instant paidAt) {}

  record RefundResult(
      String refundId, String paymentId, String status, long amountCent, String channelId) {}

  record Notification(String id, String payloadHash, Result payment, RefundResult refund) {}

  record BillRow(
      String type,
      String id,
      String channelId,
      long amountCent,
      String status,
      Long channelFeeCent) {}

  Result create(Request request);

  Result query(String paymentId);

  Result close(String paymentId);

  RefundResult refund(
      String refundId, String paymentId, long amountCent, long totalCent, String reason);

  RefundResult queryRefund(String refundId);

  Notification notification(Map<String, String> headers, String body);

  List<BillRow> bill(LocalDate day);
}
