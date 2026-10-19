package com.framehub.video.commerce.service;

import com.framehub.video.commerce.config.CommerceProperties;
import com.framehub.video.commerce.domain.*;
import com.framehub.video.commerce.domain.CommerceEntities.*;
import com.framehub.video.commerce.dto.CommerceRequests.RefundRequest;
import com.framehub.video.commerce.mapper.*;
import com.framehub.video.commerce.provider.PaymentProvider;
import com.framehub.video.common.ApiException;
import com.framehub.video.messaging.DomainOutbox;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentTransactions {
  private final TradeMapper db;
  private final CatalogMapper catalog;
  private final OrderService orders;
  private final CatalogService audit;
  private final CommerceProperties p;
  private final DomainOutbox events;

  public PaymentTransactions(
      TradeMapper db,
      CatalogMapper catalog,
      OrderService orders,
      CatalogService audit,
      CommerceProperties p,
      DomainOutbox events) {
    this.db = db;
    this.catalog = catalog;
    this.orders = orders;
    this.audit = audit;
    this.p = p;
    this.events = events;
  }

  @Transactional
  public Payment prepare(long user, String orderId, String provider) {
    orders.owned(user, orderId);
    Order o = db.lockOrder(orderId);
    Payment old = db.paymentForOrder(orderId);
    if (old != null) {
      if (!old.provider.equals(provider)) throw new ApiException(409, "订单已绑定另一支付渠道");
      return old;
    }
    if (!o.status.equals("PENDING") || !o.expiresAt.isAfter(Instant.now()))
      throw new ApiException(409, "订单已过期或不可支付");
    Payment payment = new Payment();
    payment.id = UUID.randomUUID().toString();
    payment.orderId = o.id;
    payment.provider = provider;
    payment.status = "CREATED";
    payment.amountCent = o.amountCent;
    payment.currency = p.currency();
    db.insertPayment(payment);
    o.status = "PAYING";
    db.update(o);
    return payment;
  }

  @Transactional
  public Payment apply(PaymentProvider.Result result, String provider) {
    Payment existing = db.payment(result.paymentId());
    if (existing == null) throw new ApiException(404, "支付单不存在");
    Order o = db.lockOrder(existing.orderId);
    Payment payment = db.payment(existing.id);
    validate(payment, result, provider);
    if (payment.status.equals("SUCCEEDED")) return payment;
    if (result.status().equals("SUCCEEDED")) {
      if (result.paidAt() == null || result.channelId() == null || result.channelId().isBlank())
        throw new ApiException(409, "渠道成功信息不完整");
      payment.status = "SUCCEEDED";
      payment.channelId = result.channelId();
      payment.paidAt = result.paidAt();
      db.paymentUpdate(payment);
      boolean closed = o.status.equals("CANCELLED");
      if (!Set.of("PENDING", "PAYING", "CLOSING", "CANCELLED").contains(o.status))
        throw new ApiException(409, "订单状态不允许支付成功");
      o.status = closed ? "REFUND_PENDING" : "PAID";
      o.paidAt = payment.paidAt;
      db.update(o);
      events.emit(
          "ORDER",
          o.id,
          "PAYMENT_SUCCEEDED",
          1,
          o.userId,
          Map.of("orderId", o.id, "paymentId", payment.id));
      audit.audit(
          o.userId,
          "PAYMENT_SUCCEEDED",
          o.id,
          Map.of("paymentId", payment.id, "provider", provider));
      if (closed) createRefund(o, payment, 0, "late-payment-" + o.id, "已关闭订单收到支付成功，自动退款");
    } else if (result.status().equals("CLOSED")) {
      payment.status = "CLOSED";
      payment.channelId = result.channelId();
      db.paymentUpdate(payment);
      if (Set.of("PENDING", "PAYING", "CLOSING").contains(o.status)) cancelled(o);
    } else if (result.status().equals("PENDING")) {
      if (payment.status.equals("CLOSED")) return payment;
      payment.status = "PENDING";
      payment.checkoutUrl = result.checkoutUrl();
      payment.channelId = result.channelId();
      db.paymentUpdate(payment);
    } else throw new ApiException(409, "未知渠道支付状态");
    return db.payment(payment.id);
  }

  private void validate(Payment p, PaymentProvider.Result r, String provider) {
    if (!p.provider.equals(provider)
        || p.amountCent != r.amountCent()
        || !p.currency.equals(r.currency())
        || p.channelId != null && r.channelId() != null && !p.channelId.equals(r.channelId()))
      throw new ApiException(409, "支付渠道身份、币种、金额或流水号不匹配");
  }

  @Transactional
  public Payment beginClose(long user, String id, boolean expired) {
    Order known = db.order(id);
    if (known == null) throw new ApiException(404, "订单不存在");
    if (!expired && known.userId != user) throw new ApiException(403, "无权取消该订单");
    var guard = orders.lockUser(known.userId);
    Order o = db.lockOrder(id);
    if (Set.of("PAID", "REFUND_PENDING", "REFUNDED").contains(o.status))
      throw new ApiException(409, "已支付订单不能取消");
    if (o.status.equals("CANCELLED")) return null;
    if (expired && o.expiresAt.isAfter(Instant.now()) && !o.status.equals("CLOSING")) return null;
    if (!o.status.equals("CLOSING")) {
      if (!expired) {
        LocalDate day = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        if (!day.equals(guard.cancelDay)) {
          guard.cancelDay = day;
          guard.cancelCount = 0;
        }
        if (guard.cancelCount >= p.dailyCancellationLimit())
          throw new ApiException(429, "今日取消订单次数已达上限");
        guard.cancelCount++;
        db.cancellation(guard);
      }
      o.status = "CLOSING";
      o.cancelReason = expired ? "EXPIRED" : "USER_CANCELLED";
      db.update(o);
    }
    Payment payment = db.paymentForOrder(id);
    if (payment == null) {
      cancelled(o);
      return null;
    }
    return payment;
  }

  private void cancelled(Order o) {
    o.status = "CANCELLED";
    db.update(o);
    if (o.orderType.equals("LISTING")) {
      Material m = catalog.lock(o.materialId);
      if (m.currentVersion == o.materialVersion && m.status.equals("PUBLISH_PAYMENT_PENDING"))
        catalog.state(m.id, m.currentVersion, "DRAFT");
    }
    audit.audit(
        o.userId,
        "ORDER_CANCELLED",
        o.id,
        Map.of("reason", Objects.toString(o.cancelReason, "CHANNEL_CLOSED")));
  }

  @Transactional
  public Refund prepareRefund(long user, String id, RefundRequest req, boolean admin) {
    Order o = db.lockOrder(id);
    if (o == null) throw new ApiException(404, "订单不存在");
    if (!admin && o.userId != user) throw new ApiException(403, "无权退款该订单");
    Refund old = db.refundForOrder(id);
    if (old != null) {
      if (!old.idempotencyKey.equals(req.idempotencyKey()) || !old.reason.equals(req.reason()))
        throw new ApiException(409, "该订单已有退款申请，请查询原退款单");
      return old;
    }
    if (!o.status.equals("PAID")) throw new ApiException(409, "仅支持已支付订单全额退款");
    Payment payment = db.paymentForOrder(id);
    o.status = "REFUND_PENDING";
    db.update(o);
    return createRefund(o, payment, user, req.idempotencyKey(), req.reason());
  }

  private Refund createRefund(Order o, Payment p, long actor, String key, String reason) {
    Refund r = new Refund();
    r.id = UUID.randomUUID().toString();
    r.orderId = o.id;
    r.paymentId = p.id;
    r.requestedBy = actor;
    r.idempotencyKey = key;
    r.amountCent = o.amountCent;
    r.status = "PENDING";
    r.reason = reason;
    db.insertRefund(r);
    audit.audit(actor, "REFUND_REQUESTED", o.id, Map.of("refundId", r.id));
    return r;
  }

  @Transactional
  public Refund applyRefund(PaymentProvider.RefundResult result, String provider) {
    Refund original = db.refund(result.refundId());
    if (original == null) throw new ApiException(404, "退款单不存在");
    Order o = db.lockOrder(original.orderId);
    Refund r = db.refund(original.id);
    Payment payment = db.payment(r.paymentId);
    if (!payment.provider.equals(provider)
        || !result.paymentId().equals(r.paymentId)
        || result.amountCent() != r.amountCent
        || r.channelId != null && !r.channelId.equals(result.channelId()))
      throw new ApiException(409, "退款渠道信息不匹配");
    if (r.status.equals("SUCCEEDED")) return r;
    if (result.status().equals("SUCCEEDED")) {
      if (result.channelId() == null || result.channelId().isBlank())
        throw new ApiException(409, "退款流水号缺失");
      r.status = "SUCCEEDED";
      r.channelId = result.channelId();
      db.refundUpdate(r);
      o.status = "REFUNDED";
      db.update(o);
      events.emit(
          "ORDER",
          o.id,
          "REFUND_SUCCEEDED",
          1,
          o.userId,
          Map.of("orderId", o.id, "refundId", r.id));
      audit.audit(r.requestedBy, "REFUND_SUCCEEDED", o.id, Map.of("refundId", r.id));
    } else if (result.status().equals("PROCESSING")) {
      r.status = "PROCESSING";
      r.channelId = result.channelId();
      db.refundUpdate(r);
    } else if (result.status().equals("FAILED")) {
      r.status = "FAILED";
      db.refundUpdate(r);
      o.status = "PAID";
      db.update(o);
    } else throw new ApiException(409, "未知退款状态");
    return r;
  }

  @Transactional
  public void notification(PaymentProvider.Notification n, String provider) {
    String payment = n.payment() != null ? n.payment().paymentId() : n.refund().paymentId();
    if (db.notification(provider, n.id(), n.payloadHash(), payment) == 0) {
      if (!Objects.equals(db.notificationHash(provider, n.id()), n.payloadHash()))
        throw new ApiException(409, "通知标识对应内容不一致");
      return;
    }
    if (n.payment() != null) apply(n.payment(), provider);
    else applyRefund(n.refund(), provider);
  }
}
