package com.framehub.video.commerce.service;

import static com.framehub.video.commerce.service.LedgerService.account;

import com.framehub.video.commerce.mapper.*;
import com.framehub.video.common.ApiException;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReconciliationTransactions {
  private final TradeMapper trades;
  private final ReconciliationMapper db;
  private final LedgerMapper journals;
  private final LedgerService ledger;
  private final CatalogService audit;

  public ReconciliationTransactions(
      TradeMapper trades,
      ReconciliationMapper db,
      LedgerMapper journals,
      LedgerService ledger,
      CatalogService audit) {
    this.trades = trades;
    this.db = db;
    this.journals = journals;
    this.ledger = ledger;
    this.audit = audit;
  }

  @Transactional
  public void actualFee(String paymentId, long fee) {
    var p = trades.payment(paymentId);
    if (p == null || fee < 0 || fee > p.amountCent) throw new ApiException(409, "渠道手续费无效");
    var o = trades.lockOrder(p.orderId);
    p = trades.payment(paymentId);
    if (journals.journal("payment:" + o.id) == null)
      throw new ApiException(409, "支付事件尚未入账，稍后重试手续费核对");
    if (p.channelFeeActualCent != null) {
      if (p.channelFeeActualCent != fee) throw new ApiException(409, "渠道手续费与先前账单不一致，需人工核对");
      return;
    }
    ledger.lockCreator(o.creatorId);
    long delta = Math.subtractExact(fee, o.channelFeeCent);
    ledger.post(
        "channel-fee:" + p.id,
        "CHANNEL_FEE_ADJUSTMENT",
        o.id,
        null,
        Map.of(account(0, "REVENUE"), -delta, account(0, "CHANNEL_FEE"), delta));
    db.actualFee(p.id, fee);
  }

  @Transactional
  public void acknowledge(long admin, String id, String resolution) {
    var d = db.lockDifference(id);
    if (d == null) throw new ApiException(404, "对账差异不存在");
    if (!d.status.equals("OPEN")) throw new ApiException(409, "仅未处理差异可标记人工核查");
    db.acknowledge(id, resolution);
    audit.audit(admin, "RECONCILIATION_ACKNOWLEDGED", id, Map.of("resolution", resolution));
  }
}
