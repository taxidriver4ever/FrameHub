package com.framehub.video.commerce.service;

import com.framehub.video.commerce.dto.CommerceRequests.RefundRequest;
import com.framehub.video.commerce.dto.CommerceViews;
import com.framehub.video.commerce.mapper.TradeMapper;
import com.framehub.video.commerce.provider.*;
import com.framehub.video.common.ApiException;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class PaymentService {
  private final PaymentTransactions tx;
  private final TradeMapper db;
  private final OrderService orders;
  private final PaymentProvider provider;

  public PaymentService(
      PaymentTransactions tx, TradeMapper db, OrderService orders, PaymentProvider provider) {
    this.tx = tx;
    this.db = db;
    this.orders = orders;
    this.provider = provider;
  }

  public CommerceViews.PaymentView pay(long user, String id) {
    if (provider.name().equals("DISABLED")) throw new ApiException(503, "支付渠道尚未配置");
    var p = tx.prepare(user, id, provider.name());
    if (Set.of("SUCCEEDED", "CLOSED").contains(p.status)) return CommerceViews.payment(p);
    var o = db.order(id);
    var result =
        provider.create(
            new PaymentProvider.Request(
                p.id, p.amountCent, p.currency, "Frame Hub " + o.orderType, o.expiresAt));
    return CommerceViews.payment(tx.apply(result, provider.name()));
  }

  public CommerceViews.PaymentView query(long user, String id) {
    var p = require(id);
    orders.owned(user, p.orderId);
    return sync(id);
  }

  public CommerceViews.PaymentView sync(String id) {
    var p = require(id);
    if (!p.provider.equals(provider.name())) throw new ApiException(409, "支付渠道配置已变化");
    PaymentProvider.Result result;
    if (p.status.equals("CREATED")) {
      var o = db.order(p.orderId);
      result =
          provider.create(
              new PaymentProvider.Request(
                  p.id, p.amountCent, p.currency, "Frame Hub " + o.orderType, o.expiresAt));
    } else result = provider.query(id);
    return CommerceViews.payment(tx.apply(result, provider.name()));
  }

  public void close(long user, String id, boolean expired) {
    var p = tx.beginClose(user, id, expired);
    if (p == null) return;
    if (!p.provider.equals(provider.name())) throw new ApiException(409, "支付渠道配置已变化");
    if (p.status.equals("CREATED")) {
      var o = db.order(id);
      tx.apply(
          provider.create(
              new PaymentProvider.Request(
                  p.id, p.amountCent, p.currency, "Frame Hub " + o.orderType, o.expiresAt)),
          provider.name());
    }
    tx.apply(provider.close(p.id), provider.name());
  }

  public CommerceViews.RefundView refund(long user, String id, RefundRequest req, boolean admin) {
    var r = tx.prepareRefund(user, id, req, admin);
    return syncRefund(r.id);
  }

  public CommerceViews.RefundView syncRefund(String id) {
    var r = db.refund(id);
    if (r == null) throw new ApiException(404, "退款单不存在");
    if (r.status.equals("SUCCEEDED") || r.status.equals("FAILED")) return CommerceViews.refund(r);
    var p = require(r.paymentId);
    if (!p.provider.equals(provider.name())) throw new ApiException(409, "支付渠道配置已变化");
    return CommerceViews.refund(
        tx.applyRefund(
            provider.refund(r.id, r.paymentId, r.amountCent, p.amountCent, r.reason),
            provider.name()));
  }

  public CommerceViews.PaymentView mockSuccess(long user, String id) {
    var p = require(id);
    orders.owned(user, p.orderId);
    if (!(provider instanceof MockPaymentProvider mock)) throw new ApiException(404, "模拟支付入口不可用");
    return CommerceViews.payment(tx.apply(mock.succeed(id), provider.name()));
  }

  public void notification(Map<String, String> headers, String body) {
    tx.notification(provider.notification(headers, body), provider.name());
  }

  private com.framehub.video.commerce.domain.CommerceEntities.Payment require(String id) {
    var p = db.payment(id);
    if (p == null) throw new ApiException(404, "支付单不存在");
    return p;
  }
}
