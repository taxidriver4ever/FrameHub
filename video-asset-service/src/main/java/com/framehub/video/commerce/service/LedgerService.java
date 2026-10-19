package com.framehub.video.commerce.service;

import com.framehub.video.commerce.domain.JsonData;
import com.framehub.video.commerce.domain.SettlementEntities.*;
import com.framehub.video.commerce.mapper.LedgerMapper;
import com.framehub.video.common.ApiException;
import java.math.BigInteger;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class LedgerService {
  private final LedgerMapper db;
  private final JsonData json;

  public LedgerService(LedgerMapper db, JsonData json) {
    this.db = db;
    this.json = json;
  }

  public static String account(long user, String bucket) {
    return (user == 0 ? "platform:" : "creator:" + user + ":") + bucket;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void lockCreator(long user) {
    db.guard(user);
    db.lockGuard(user);
    for (String bucket : List.of("AVAILABLE", "DEBT", "FROZEN", "PAYOUT_PENDING"))
      db.ensure(account(user, bucket), user, bucket);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public long balance(long user, String bucket) {
    return db.lock(account(user, bucket)).balanceCent;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public boolean post(
      String key, String kind, String reference, String event, Map<String, Long> changes) {
    TreeMap<String, Long> entries = new TreeMap<>();
    changes.forEach(
        (k, v) -> {
          if (v != 0) entries.merge(k, v, Math::addExact);
        });
    entries.values().removeIf(v -> v == 0);
    BigInteger sum =
        entries.values().stream().map(BigInteger::valueOf).reduce(BigInteger.ZERO, BigInteger::add);
    if (!sum.equals(BigInteger.ZERO)) throw new IllegalArgumentException("Unbalanced journal");
    String hash = json.hash(List.of(kind, reference, entries));
    Journal old = db.journal(key);
    if (old != null) {
      if (!old.payloadHash.equals(hash))
        throw new IllegalStateException("Journal idempotency conflict");
      return false;
    }
    for (String id : entries.keySet()) {
      String[] parts = id.split(":");
      long owner = parts[0].equals("platform") ? 0 : Long.parseLong(parts[1]);
      String bucket = parts[parts.length - 1];
      db.ensure(id, owner, bucket);
    }
    Journal j = new Journal();
    j.id = UUID.randomUUID().toString();
    j.businessKey = key;
    j.kind = kind;
    j.referenceId = reference;
    j.sourceEvent = event;
    j.payloadHash = hash;
    db.journalInsert(j);
    for (var entry : entries.entrySet()) {
      Account a = db.lock(entry.getKey());
      a.balanceCent = Math.addExact(a.balanceCent, entry.getValue());
      if (a.ownerId != 0 && !a.bucket.equals("DEBT") && a.balanceCent < 0)
        throw new ApiException(409, "可用资金不足");
      if (db.update(a) != 1) throw new IllegalStateException("Ledger account version conflict");
      db.entry(j.id, a.id, entry.getValue(), a.balanceCent);
    }
    return true;
  }

  public Map<String, Object> balances(long user) {
    Map<String, Long> values = new HashMap<>();
    db.accounts(user).forEach(a -> values.put(a.bucket, a.balanceCent));
    return Map.of(
        "frozenCent",
        values.getOrDefault("FROZEN", 0L),
        "availableCent",
        values.getOrDefault("AVAILABLE", 0L),
        "payoutPendingCent",
        values.getOrDefault("PAYOUT_PENDING", 0L),
        "debtCent",
        Math.negateExact(values.getOrDefault("DEBT", 0L)));
  }

  public List<Map<String, Object>> history(long user, int page, int size) {
    CatalogService.page(page, size);
    return db.history(user, size, (long) (page - 1) * size);
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Map<String, Object> verify() {
    return Map.of("accountMismatches", db.mismatches(), "unbalancedJournals", db.unbalanced());
  }
}
