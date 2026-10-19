package com.framehub.video.commerce.service;

import static com.framehub.video.commerce.service.LedgerService.account;

import com.framehub.video.commerce.domain.*;
import com.framehub.video.commerce.domain.CommerceEntities.*;
import com.framehub.video.commerce.domain.SettlementEntities.Freeze;
import com.framehub.video.commerce.mapper.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class SettlementService {
  private final TradeMapper trades;
  private final CatalogMapper catalog;
  private final LedgerMapper db;
  private final LedgerService ledger;
  private final EntitlementMapper grants;
  private final JsonData json;

  public SettlementService(
      TradeMapper trades,
      CatalogMapper catalog,
      LedgerMapper db,
      LedgerService ledger,
      EntitlementMapper grants,
      JsonData json) {
    this.trades = trades;
    this.catalog = catalog;
    this.db = db;
    this.ledger = ledger;
    this.grants = grants;
    this.json = json;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void paid(String orderId, String event) {
    Order o = trades.lockOrder(orderId);
    if (o == null || o.paidAt == null)
      throw new IllegalStateException("Payment event has no paid order");
    var payment = trades.paymentForOrder(orderId);
    if (payment == null || !payment.status.equals("SUCCEEDED"))
      throw new IllegalStateException("Payment not confirmed");
    ledger.lockCreator(o.creatorId);
    Map<String, Long> entries = new HashMap<>();
    entries.put(account(0, "CHANNEL"), -o.amountCent);
    entries.put(account(0, "REVENUE"), o.commissionCent);
    entries.put(account(0, "CHANNEL_FEE"), o.channelFeeCent);
    entries.put(account(o.creatorId, "FROZEN"), o.creatorCent);
    boolean first = ledger.post("payment:" + o.id, "PAYMENT_CAPTURE", o.id, event, entries);
    if (o.orderType.equals("LISTING")) {
      Material m = catalog.lock(o.materialId);
      if (o.status.equals("PAID")
          && m.currentVersion == o.materialVersion
          && m.status.equals("PUBLISH_PAYMENT_PENDING"))
        catalog.state(m.id, m.currentVersion, "PUBLISHED");
      return;
    }
    if (first) {
      Freeze f = new Freeze();
      f.orderId = o.id;
      f.userId = o.creatorId;
      f.amountCent = o.creatorCent;
      f.releaseAt =
          o.paidAt.plusSeconds(json.read(o.feeSnapshot).path("freezeSeconds").longValue());
      f.status = "FROZEN";
      db.freeze(f);
    }
    if (grants.byOrder(o.id) == null) {
      MaterialVersion v = catalog.versionAt(o.materialId, o.materialVersion);
      Entitlement e = new Entitlement();
      e.id = UUID.randomUUID().toString();
      e.orderId = o.id;
      e.userId = o.userId;
      e.materialId = o.materialId;
      e.materialVersion = o.materialVersion;
      e.status = "ACTIVE";
      e.licenseSnapshot = o.licenseSnapshot;
      e.sourceKey = v.sourceKey;
      int days = json.read(o.licenseSnapshot).path("terms").path("durationDays").intValue();
      e.expiresAt = days == 0 ? null : o.paidAt.plus(Duration.ofDays(days));
      grants.insert(e);
    }
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void refunded(String orderId, String event) {
    Order o = trades.lockOrder(orderId);
    if (o == null || !o.status.equals("REFUNDED"))
      throw new IllegalStateException("Refund event has no completed refund");
    var refund = trades.refundForOrder(orderId);
    if (refund == null || !refund.status.equals("SUCCEEDED"))
      throw new IllegalStateException("Refund not confirmed");
    paid(orderId, event);
    if (db.journal("refund:" + o.id) != null) return;
    ledger.lockCreator(o.creatorId);
    Map<String, Long> entries = new HashMap<>();
    entries.put(account(0, "CHANNEL"), o.amountCent);
    entries.put(account(0, "REVENUE"), -Math.addExact(o.commissionCent, o.channelFeeCent));
    Freeze f = db.freezeAt(o.id);
    if (o.creatorCent > 0) {
      if (f != null && f.status.equals("FROZEN"))
        entries.put(account(o.creatorId, "FROZEN"), -o.creatorCent);
      else {
        long available = ledger.balance(o.creatorId, "AVAILABLE"),
            take = Math.min(available, o.creatorCent);
        entries.put(account(o.creatorId, "AVAILABLE"), -take);
        entries.put(account(o.creatorId, "DEBT"), -(o.creatorCent - take));
      }
    }
    ledger.post("refund:" + o.id, "REFUND_REVERSAL", o.id, event, entries);
    if (f != null) {
      f.status = "REVERSED";
      db.freezeUpdate(f);
    }
    grants.revoke(o.id, "全额退款");
    if (o.orderType.equals("LISTING")) {
      Material m = catalog.lock(o.materialId);
      if (m.currentVersion == o.materialVersion
          && Set.of("PUBLISHED", "PUBLISH_PAYMENT_PENDING").contains(m.status))
        catalog.state(m.id, m.currentVersion, "OFF_SHELF");
    }
  }

  @Transactional
  public boolean release(String orderId) {
    Order o = trades.lockOrder(orderId);
    if (o == null || !o.status.equals("PAID")) return false;
    Freeze f = db.freezeAt(orderId);
    if (f == null || !f.status.equals("FROZEN") || f.releaseAt.isAfter(Instant.now())) return false;
    ledger.lockCreator(f.userId);
    long debt = Math.negateExact(ledger.balance(f.userId, "DEBT")),
        repay = Math.min(Math.max(0, debt), f.amountCent);
    ledger.post(
        "release:" + o.id,
        "EARNING_RELEASE",
        o.id,
        null,
        Map.of(
            account(f.userId, "FROZEN"),
            -f.amountCent,
            account(f.userId, "DEBT"),
            repay,
            account(f.userId, "AVAILABLE"),
            f.amountCent - repay));
    f.status = "RELEASED";
    f.releasedAt = Instant.now();
    db.freezeUpdate(f);
    return true;
  }
}
