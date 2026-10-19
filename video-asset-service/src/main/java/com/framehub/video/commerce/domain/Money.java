package com.framehub.video.commerce.domain;

import com.framehub.video.common.ApiException;
import java.math.*;

public final class Money {
  public static final long MAX = 1_000_000_000_000L;

  private Money() {}

  public static long amount(long value) {
    if (value < 1 || value > MAX) throw new ApiException(400, "金额必须为 1 至 1000000000000 整数分");
    return value;
  }

  public static long rate(long amount, int bps) {
    if (amount < 0 || amount > MAX || bps < 0 || bps > 10000)
      throw new ApiException(400, "费率或金额不合法");
    return BigDecimal.valueOf(amount)
        .multiply(BigDecimal.valueOf(bps))
        .divide(BigDecimal.valueOf(10000), 0, RoundingMode.HALF_UP)
        .longValueExact();
  }

  public record Fees(
      long grossCent,
      long commissionCent,
      long channelFeeCent,
      long creatorCent,
      int ruleVersion,
      int commissionBps,
      int channelFeeBps,
      long freezeSeconds,
      String channelFeeBearer) {}

  public static Fees fees(long amount, CommerceEntities.FeeRule rule, boolean listing) {
    amount(amount);
    if (rule.commissionBps + rule.channelFeeBps > 10000 || rule.freezeSeconds < 0)
      throw new ApiException(400, "收费规则不合法");
    long channel = rate(amount, rule.channelFeeBps),
        commission = listing ? amount - channel : rate(amount, rule.commissionBps);
    long net = Math.subtractExact(amount, Math.addExact(commission, channel));
    if (net < 0) throw new ApiException(400, "舍入后的费用超过售价");
    return new Fees(
        amount,
        commission,
        channel,
        net,
        rule.version,
        rule.commissionBps,
        rule.channelFeeBps,
        rule.freezeSeconds,
        listing ? "PLATFORM" : "CREATOR");
  }
}
