package com.framehub.video.commerce.provider;

import com.framehub.video.commerce.mapper.MockChannelMapper;
import com.framehub.video.commerce.mapper.MockChannelMapper.*;
import com.framehub.video.common.ApiException;
import java.time.*;
import java.util.*;
import org.springframework.transaction.annotation.*;

/**
 * Persistent simulated channel. Its transactions deliberately commit independently of business
 * state.
 */
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class MockPaymentProvider implements PaymentProvider {
  private final MockChannelMapper db;

  public MockPaymentProvider(MockChannelMapper db) {
    this.db = db;
  }

  public String name() {
    return "MOCK";
  }

  public Result create(Request r) {
    db.create(r.paymentId(), r.amountCent(), "mock-" + r.paymentId());
    var p = db.lock(r.paymentId());
    if (p.amountCent != r.amountCent() || !r.currency().equals("CNY"))
      throw new ApiException(409, "模拟渠道金额不一致");
    return result(p);
  }

  public Result query(String id) {
    return result(require(id));
  }

  public Result close(String id) {
    var p = db.lock(id);
    if (p == null) throw new ApiException(404, "渠道支付单不存在");
    if (p.status.equals("PENDING")) {
      p.status = "CLOSED";
      db.update(p);
    }
    return result(p);
  }

  public Result succeed(String id) {
    var p = db.lock(id);
    if (p == null) throw new ApiException(404, "渠道支付单不存在");
    if (p.status.equals("CLOSED")) throw new ApiException(409, "渠道支付单已关闭");
    if (!p.status.equals("SUCCEEDED")) {
      p.status = "SUCCEEDED";
      p.paidAt = Instant.now();
      db.update(p);
    }
    return result(p);
  }

  public RefundResult refund(String id, String payment, long amount, long total, String reason) {
    var p = db.lock(payment);
    if (p == null || !p.status.equals("SUCCEEDED")) throw new ApiException(409, "渠道尚未支付");
    var old = db.refundAt(id);
    if (old != null) {
      if (!old.paymentId.equals(payment) || old.amountCent != amount)
        throw new ApiException(409, "渠道退款幂等键冲突");
      return result(old);
    }
    if (total != p.amountCent
        || amount <= 0
        || Math.addExact(db.refunded(payment), amount) > p.amountCent)
      throw new ApiException(409, "渠道退款金额异常");
    var r = new ChannelRefund();
    r.refundId = id;
    r.paymentId = payment;
    r.amountCent = amount;
    r.status = "SUCCEEDED";
    r.channelId = "mock-refund-" + id;
    db.refund(r);
    return result(r);
  }

  public RefundResult queryRefund(String id) {
    var r = db.refundAt(id);
    if (r == null) throw new ApiException(404, "渠道退款单不存在");
    return result(r);
  }

  public Notification notification(Map<String, String> headers, String body) {
    throw new ApiException(404, "模拟渠道不接收公网回调，请使用开发环境认证入口");
  }

  public List<BillRow> bill(LocalDate day) {
    Instant from = day.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant(),
        to = day.plusDays(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
    List<BillRow> rows = new ArrayList<>();
    db.payments(from, to)
        .forEach(
            p ->
                rows.add(
                    new BillRow(
                        "PAYMENT", p.paymentId, p.channelId, p.amountCent, p.status, null)));
    db.refunds(from, to)
        .forEach(
            r ->
                rows.add(
                    new BillRow("REFUND", r.refundId, r.channelId, r.amountCent, r.status, null)));
    return rows;
  }

  private ChannelPayment require(String id) {
    var p = db.payment(id);
    if (p == null) throw new ApiException(404, "渠道支付单不存在");
    return p;
  }

  private Result result(ChannelPayment p) {
    return new Result(
        p.paymentId,
        p.status,
        p.amountCent,
        "CNY",
        p.channelId,
        "mock://payment/" + p.paymentId,
        p.paidAt);
  }

  private RefundResult result(ChannelRefund r) {
    return new RefundResult(r.refundId, r.paymentId, r.status, r.amountCent, r.channelId);
  }
}
