package com.framehub.video.commerce.service;

import com.framehub.video.commerce.domain.SettlementEntities.Withdrawal;
import com.framehub.video.commerce.dto.CommerceRequests.*;
import com.framehub.video.commerce.mapper.WithdrawalMapper;
import com.framehub.video.commerce.provider.*;
import com.framehub.video.common.ApiException;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class WithdrawalService {
  private final WithdrawalTransactions tx;
  private final WithdrawalMapper db;
  private final WithdrawalProvider provider;

  public WithdrawalService(
      WithdrawalTransactions tx, WithdrawalMapper db, WithdrawalProvider provider) {
    this.tx = tx;
    this.db = db;
    this.provider = provider;
  }

  public record View(
      String id, String provider, long amountCent, String status, Instant createdAt) {}

  public View create(long user, WithdrawalRequest req) {
    if (provider.name().equals("DISABLED")) throw new ApiException(503, "提现渠道尚未配置");
    return sync(tx.create(user, req, provider.name()).id);
  }

  public View sync(String id) {
    Withdrawal w = db.get(id);
    if (w == null) throw new ApiException(404, "提现单不存在");
    if (Set.of("SUCCEEDED", "FAILED").contains(w.status)) return view(w);
    if (!w.provider.equals(provider.name())) throw new ApiException(409, "提现渠道配置已变化");
    var result =
        w.status.equals("PENDING")
            ? provider.submit(new WithdrawalProvider.Request(w.id, w.amountCent, w.accountSnapshot))
            : provider.query(id);
    return view(tx.apply(result, provider.name()));
  }

  public List<View> mine(long user, int page, int size) {
    CatalogService.page(page, size);
    return db.mine(user, size, (long) (page - 1) * size).stream().map(this::view).toList();
  }

  public View get(long user, String id) {
    owned(user, id);
    return sync(id);
  }

  public View mockComplete(long user, String id, boolean success) {
    owned(user, id);
    if (!(provider instanceof MockWithdrawalProvider mock))
      throw new ApiException(404, "模拟提现入口不可用");
    return view(tx.apply(mock.complete(id, success), provider.name()));
  }

  public void saveAccount(long user, WithdrawalAccount req) {
    tx.saveAccount(user, req);
  }

  public Map<String, Object> account(long user) {
    var a = db.account(user);
    if (a == null) return Map.of("configured", false);
    String value = a.providerAccount;
    return Map.of(
        "configured",
        true,
        "displayName",
        a.displayName,
        "providerAccount",
        "***" + value.substring(Math.max(0, value.length() - 4)),
        "provider",
        provider.name());
  }

  private Withdrawal owned(long user, String id) {
    var w = db.get(id);
    if (w == null) throw new ApiException(404, "提现单不存在");
    if (w.userId != user) throw new ApiException(403, "无权操作该提现单");
    return w;
  }

  private View view(Withdrawal w) {
    return new View(w.id, w.provider, w.amountCent, w.status, w.createdAt);
  }
}
