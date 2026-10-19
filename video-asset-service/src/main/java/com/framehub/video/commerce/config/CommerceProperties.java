package com.framehub.video.commerce.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("commerce")
public record CommerceProperties(
    String paymentProvider,
    String withdrawalProvider,
    boolean jobsEnabled,
    int maxPendingOrders,
    int dailyCancellationLimit,
    Duration orderTtl,
    Duration downloadTtl,
    String currency) {
  public CommerceProperties {
    if (paymentProvider == null) paymentProvider = "DISABLED";
    if (withdrawalProvider == null) withdrawalProvider = "DISABLED";
    if (maxPendingOrders < 1) maxPendingOrders = 5;
    if (dailyCancellationLimit < 1) dailyCancellationLimit = 10;
    if (orderTtl == null) orderTtl = Duration.ofMinutes(15);
    if (downloadTtl == null) downloadTtl = Duration.ofMinutes(2);
    if (currency == null) currency = "CNY";
    if (!currency.equals("CNY")) throw new IllegalArgumentException("Only CNY is supported");
  }
}
