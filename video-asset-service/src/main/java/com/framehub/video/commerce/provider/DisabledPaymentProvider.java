package com.framehub.video.commerce.provider;

import com.framehub.video.common.ApiException;
import java.time.*;
import java.util.*;

public class DisabledPaymentProvider implements PaymentProvider {
  public String name() {
    return "DISABLED";
  }

  private ApiException unavailable() {
    return new ApiException(503, "支付渠道尚未配置");
  }

  public Result create(Request r) {
    throw unavailable();
  }

  public Result query(String id) {
    throw unavailable();
  }

  public Result close(String id) {
    throw unavailable();
  }

  public RefundResult refund(String id, String payment, long amount, long total, String reason) {
    throw unavailable();
  }

  public RefundResult queryRefund(String id) {
    throw unavailable();
  }

  public Notification notification(Map<String, String> h, String b) {
    throw unavailable();
  }

  public List<BillRow> bill(LocalDate d) {
    throw unavailable();
  }
}
