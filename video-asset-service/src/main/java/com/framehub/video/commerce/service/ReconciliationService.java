package com.framehub.video.commerce.service;

import com.framehub.video.commerce.domain.JsonData;
import com.framehub.video.commerce.domain.SettlementEntities.*;
import com.framehub.video.commerce.mapper.*;
import com.framehub.video.commerce.provider.PaymentProvider;
import com.framehub.video.commerce.provider.PaymentProvider.BillRow;
import com.framehub.video.common.ApiException;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class ReconciliationService {
  private final PaymentProvider provider;
  private final ReconciliationMapper db;
  private final TradeMapper trades;
  private final PaymentService payments;
  private final ReconciliationTransactions tx;
  private final JsonData json;
  private final CatalogService audit;

  public ReconciliationService(
      PaymentProvider provider,
      ReconciliationMapper db,
      TradeMapper trades,
      PaymentService payments,
      ReconciliationTransactions tx,
      JsonData json,
      CatalogService audit) {
    this.provider = provider;
    this.db = db;
    this.trades = trades;
    this.payments = payments;
    this.tx = tx;
    this.json = json;
    this.audit = audit;
  }

  public record BatchView(
      String id,
      String provider,
      LocalDate billDate,
      String status,
      String source,
      Instant createdAt,
      Instant completedAt) {}

  public record DifferenceView(
      String id,
      String recordType,
      String referenceId,
      String kind,
      String status,
      com.fasterxml.jackson.databind.JsonNode details,
      String resolution) {}

  public BatchView channel(long admin, LocalDate day) {
    validateDay(day);
    return run(admin, day, provider.bill(day), "CHANNEL");
  }

  public BatchView fixture(long admin, LocalDate day, List<BillRow> rows) {
    validateDay(day);
    return run(admin, day, rows, "FIXTURE");
  }

  private void validateDay(LocalDate day) {
    if (day == null
        || day.isAfter(LocalDate.now(ZoneId.of("Asia/Shanghai")))
        || day.isBefore(LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(90)))
      throw new ApiException(400, "对账日期必须在最近 90 日内");
  }

  private BatchView run(long admin, LocalDate day, List<BillRow> rows, String source) {
    if (rows == null || rows.size() > 100000) throw new ApiException(400, "账单行数无效");
    TreeMap<String, BillRow> indexed = new TreeMap<>();
    for (BillRow row : rows) {
      if (row == null
          || !Set.of("PAYMENT", "REFUND").contains(row.type())
          || !"SUCCEEDED".equals(row.status())
          || row.id() == null
          || !row.id().matches("[0-9a-fA-F-]{36}")
          || row.channelId() == null
          || row.channelId().isBlank()
          || row.channelId().length() > 128
          || row.amountCent() < 1
          || row.channelFeeCent() != null
              && (row.channelFeeCent() < 0 || row.channelFeeCent() > row.amountCent()))
        throw new ApiException(400, "账单行格式无效");
      String key = row.type() + ":" + row.id();
      var previous = indexed.putIfAbsent(key, row);
      if (previous != null && !previous.equals(row)) throw new ApiException(400, "账单含冲突重复记录");
    }
    Batch b = new Batch();
    b.id = UUID.randomUUID().toString();
    b.provider = provider.name();
    b.billDate = day;
    b.billHash = json.hash(List.of(source, indexed));
    b.status = "RUNNING";
    b.source = source;
    b.createdBy = admin;
    db.create(b);
    b = db.find(b.provider, day, b.billHash);
    db.status(b.id, "RUNNING");
    try {
      for (BillRow row : indexed.values())
        if (row.type().equals("PAYMENT")) payment(b, row, source);
        else refund(b, row);
      Instant from = day.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant(),
          to = day.plusDays(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
      for (var p : db.payments(provider.name(), from, to))
        if (!indexed.containsKey("PAYMENT:" + p.id))
          difference(
              b, "PAYMENT", p.id, "CHANNEL_MISSING", Map.of("platformAmountCent", p.amountCent));
      for (var r : db.refunds(provider.name(), from, to))
        if (!indexed.containsKey("REFUND:" + r.id))
          difference(
              b, "REFUND", r.id, "CHANNEL_MISSING", Map.of("platformAmountCent", r.amountCent));
      db.status(b.id, "COMPLETED");
      audit.audit(
          admin,
          "RECONCILIATION_COMPLETED",
          b.id,
          Map.of("source", source, "rows", indexed.size()));
      b.status = "COMPLETED";
      b.completedAt = Instant.now();
      return view(b);
    } catch (Exception e) {
      db.status(b.id, "FAILED");
      throw e;
    }
  }

  private void payment(Batch b, BillRow row, String source) {
    var p = trades.payment(row.id());
    if (p == null || !p.provider.equals(provider.name())) {
      difference(b, row.type(), row.id(), "PLATFORM_MISSING", row);
      return;
    }
    if (p.amountCent != row.amountCent()) {
      difference(
          b,
          row.type(),
          row.id(),
          "AMOUNT_MISMATCH",
          Map.of("platformAmountCent", p.amountCent, "channel", row));
      return;
    }
    if (p.channelId != null && !p.channelId.equals(row.channelId())) {
      difference(b, row.type(), row.id(), "CHANNEL_ID_MISMATCH", row);
      return;
    }
    if (!p.status.equals("SUCCEEDED")) {
      difference(b, row.type(), row.id(), "STATUS_MISMATCH", row);
      try {
        var verified = payments.sync(p.id);
        if (verified.status().equals("SUCCEEDED")
            && Objects.equals(trades.payment(p.id).channelId, row.channelId()))
          repaired(b, row, "STATUS_MISMATCH", "已通过渠道主动查单确认后补偿");
      } catch (Exception ignored) {
        /* Durable OPEN difference remains for retry or manual review. */
      }
    }
    if (row.channelFeeCent() != null) {
      long current =
          p.channelFeeActualCent != null
              ? p.channelFeeActualCent
              : trades.order(p.orderId).channelFeeCent;
      if (current != row.channelFeeCent()) {
        difference(
            b,
            row.type(),
            row.id(),
            "CHANNEL_FEE_MISMATCH",
            Map.of("platformFeeCent", current, "channelFeeCent", row.channelFeeCent()));
        if (source.equals("CHANNEL"))
          try {
            tx.actualFee(p.id, row.channelFeeCent());
            repaired(b, row, "CHANNEL_FEE_MISMATCH", "已追加渠道手续费调整分录，原订单快照不变");
          } catch (Exception ignored) {
          }
      }
    }
  }

  private void refund(Batch b, BillRow row) {
    var r = trades.refund(row.id());
    if (r == null) {
      difference(b, row.type(), row.id(), "PLATFORM_MISSING", row);
      return;
    }
    var p = trades.payment(r.paymentId);
    if (!p.provider.equals(provider.name())) {
      difference(b, row.type(), row.id(), "PROVIDER_MISMATCH", row);
      return;
    }
    if (r.amountCent != row.amountCent()) {
      difference(b, row.type(), row.id(), "AMOUNT_MISMATCH", row);
      return;
    }
    if (r.channelId != null && !r.channelId.equals(row.channelId())) {
      difference(b, row.type(), row.id(), "CHANNEL_ID_MISMATCH", row);
      return;
    }
    if (!r.status.equals("SUCCEEDED")) {
      difference(b, row.type(), row.id(), "STATUS_MISMATCH", row);
      try {
        var verified = payments.syncRefund(r.id);
        if (verified.status().equals("SUCCEEDED")
            && Objects.equals(trades.refund(r.id).channelId, row.channelId()))
          repaired(b, row, "STATUS_MISMATCH", "已通过渠道退款查询/幂等退款请求补偿");
      } catch (Exception ignored) {
      }
    }
  }

  private void difference(Batch b, String type, String id, String kind, Object details) {
    Difference d = new Difference();
    d.id = UUID.randomUUID().toString();
    d.batchId = b.id;
    d.recordType = type;
    d.referenceId = id;
    d.kind = kind;
    d.status = "OPEN";
    d.details = json.write(details);
    db.difference(d);
  }

  private void repaired(Batch b, BillRow row, String kind, String message) {
    db.resolution(b.id, row.type(), row.id(), kind, "REPAIRED", message);
  }

  public List<BatchView> batches(int page, int size) {
    CatalogService.page(page, size);
    return db.batches(size, (long) (page - 1) * size).stream().map(this::view).toList();
  }

  public List<DifferenceView> differences(String id) {
    return db.differences(id).stream()
        .map(
            d ->
                new DifferenceView(
                    d.id,
                    d.recordType,
                    d.referenceId,
                    d.kind,
                    d.status,
                    json.read(d.details),
                    d.resolution))
        .toList();
  }

  public void acknowledge(long admin, String id, String text) {
    tx.acknowledge(admin, id, text);
  }

  private BatchView view(Batch b) {
    return new BatchView(
        b.id, b.provider, b.billDate, b.status, b.source, b.createdAt, b.completedAt);
  }
}
