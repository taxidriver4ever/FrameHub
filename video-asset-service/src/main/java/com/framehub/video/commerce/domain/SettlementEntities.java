package com.framehub.video.commerce.domain;

import java.time.*;

public final class SettlementEntities {
  public static class Account {
    public String id, bucket;
    public long ownerId, balanceCent, version;
  }

  public static class Journal {
    public String id, businessKey, kind, referenceId, sourceEvent, payloadHash;
    public Instant createdAt;
  }

  public static class Freeze {
    public String orderId, status;
    public long userId, amountCent;
    public Instant releaseAt, createdAt, releasedAt;
  }

  public static class WithdrawalAccount {
    public long userId;
    public String displayName, providerAccount;
  }

  public static class Withdrawal {
    public String id,
        idempotencyKey,
        requestHash,
        provider,
        accountSnapshot,
        status,
        channelId,
        lastError;
    public long userId, amountCent;
    public int ruleVersion;
    public Instant createdAt, updatedAt;
  }

  public static class Batch {
    public String id, provider, billHash, status, source;
    public LocalDate billDate;
    public long createdBy;
    public Instant createdAt, completedAt;
  }

  public static class Difference {
    public String id, batchId, recordType, referenceId, kind, status, details, resolution;
    public Instant createdAt, updatedAt;
  }
}
