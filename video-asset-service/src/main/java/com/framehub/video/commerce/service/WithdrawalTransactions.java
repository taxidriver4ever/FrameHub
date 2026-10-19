package com.framehub.video.commerce.service;

import static com.framehub.video.commerce.service.LedgerService.account;

import com.framehub.video.commerce.domain.*;
import com.framehub.video.commerce.domain.SettlementEntities.*;
import com.framehub.video.commerce.dto.CommerceRequests.WithdrawalRequest;
import com.framehub.video.commerce.mapper.*;
import com.framehub.video.commerce.provider.WithdrawalProvider;
import com.framehub.video.common.ApiException;
import com.framehub.video.messaging.DomainOutbox;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WithdrawalTransactions {
  private final WithdrawalMapper db;
  private final CatalogMapper catalog;
  private final LedgerService ledger;
  private final JsonData json;
  private final CatalogService audit;
  private final DomainOutbox events;

  public WithdrawalTransactions(
      WithdrawalMapper db,
      CatalogMapper catalog,
      LedgerService ledger,
      JsonData json,
      CatalogService audit,
      DomainOutbox events) {
    this.db = db;
    this.catalog = catalog;
    this.ledger = ledger;
    this.json = json;
    this.audit = audit;
    this.events = events;
  }

  @Transactional
  public Withdrawal create(long user, WithdrawalRequest req, String provider) {
    ledger.lockCreator(user);
    String hash = json.hash(List.of(req.amountCent(), provider));
    var old = db.idempotent(user, req.idempotencyKey());
    if (old != null) {
      if (!old.requestHash.equals(hash)) throw new ApiException(409, "提现幂等键已被其他请求使用");
      return old;
    }
    var rule = catalog.currentRule();
    Money.amount(req.amountCent());
    if (req.amountCent() < rule.minimumWithdrawalCent) throw new ApiException(400, "未达到最低提现金额");
    if (ledger.balance(user, "DEBT") < 0) throw new ApiException(409, "存在待追偿金额，暂不能提现");
    if (ledger.balance(user, "AVAILABLE") < req.amountCent()) throw new ApiException(409, "可用余额不足");
    var a = db.account(user);
    if (a == null) throw new ApiException(409, "请先配置提现账户");
    Withdrawal w = new Withdrawal();
    w.id = UUID.randomUUID().toString();
    w.userId = user;
    w.idempotencyKey = req.idempotencyKey();
    w.requestHash = hash;
    w.provider = provider;
    w.accountSnapshot =
        json.write(Map.of("displayName", a.displayName, "providerAccount", a.providerAccount));
    w.amountCent = req.amountCent();
    w.ruleVersion = rule.version;
    w.status = "PENDING";
    db.insert(w);
    ledger.post(
        "withdraw-reserve:" + w.id,
        "WITHDRAWAL_RESERVED",
        w.id,
        null,
        Map.of(
            account(user, "AVAILABLE"),
            -w.amountCent,
            account(user, "PAYOUT_PENDING"),
            w.amountCent));
    audit.audit(
        user,
        "WITHDRAWAL_REQUESTED",
        w.id,
        Map.of("amountCent", w.amountCent, "provider", provider));
    return w;
  }

  @Transactional
  public Withdrawal apply(WithdrawalProvider.Result result, String provider) {
    var known = db.get(result.id());
    if (known == null) throw new ApiException(404, "提现单不存在");
    ledger.lockCreator(known.userId);
    var w = db.lock(known.id);
    if (!w.provider.equals(provider)
        || w.amountCent != result.amountCent()
        || result.channelId() == null
        || w.channelId != null && !w.channelId.equals(result.channelId()))
      throw new ApiException(409, "提现渠道信息不匹配");
    if (Set.of("SUCCEEDED", "FAILED").contains(w.status)) {
      if (!w.status.equals(result.status())) throw new ApiException(409, "提现终态不一致，需对账处理");
      return w;
    }
    Map<String, Long> changes = new HashMap<>();
    if (result.status().equals("SUCCEEDED")) {
      changes.put(account(w.userId, "PAYOUT_PENDING"), -w.amountCent);
      changes.put(account(0, "CHANNEL"), w.amountCent);
      ledger.post("withdraw-result:" + w.id, "WITHDRAWAL_SUCCEEDED", w.id, null, changes);
      events.emit(
          "WITHDRAWAL", w.id, "WITHDRAWAL_SUCCEEDED", 1, w.userId, Map.of("withdrawalId", w.id));
    } else if (result.status().equals("FAILED")) {
      long debt = Math.max(0, Math.negateExact(ledger.balance(w.userId, "DEBT"))),
          repay = Math.min(w.amountCent, debt);
      changes.put(account(w.userId, "PAYOUT_PENDING"), -w.amountCent);
      changes.put(account(w.userId, "DEBT"), repay);
      changes.put(account(w.userId, "AVAILABLE"), w.amountCent - repay);
      ledger.post("withdraw-result:" + w.id, "WITHDRAWAL_FAILED", w.id, null, changes);
    } else if (!result.status().equals("PROCESSING")) throw new ApiException(409, "未知提现状态");
    w.status = result.status();
    w.channelId = result.channelId();
    db.update(w);
    if (!w.status.equals("PROCESSING"))
      audit.audit(w.userId, "WITHDRAWAL_" + w.status, w.id, Map.of("provider", provider));
    return w;
  }

  @Transactional
  public void saveAccount(
      long user, com.framehub.video.commerce.dto.CommerceRequests.WithdrawalAccount req) {
    ledger.lockCreator(user);
    WithdrawalAccount a = new WithdrawalAccount();
    a.userId = user;
    a.displayName = req.displayName();
    a.providerAccount = req.providerAccount();
    db.accountSave(a);
    audit.audit(user, "WITHDRAWAL_ACCOUNT_UPDATED", Long.toString(user), Map.of());
  }
}
