package com.framehub.video.commerce.config;

import com.framehub.video.commerce.mapper.MockChannelMapper;
import com.framehub.video.commerce.provider.*;
import java.util.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@Configuration
@EnableConfigurationProperties({CommerceProperties.class, WechatProperties.class})
public class CommerceConfig {
  @Bean
  PaymentProvider paymentProvider(
      CommerceProperties p,
      Environment env,
      MockChannelMapper mock,
      WechatProperties wechat,
      com.framehub.video.commerce.domain.JsonData json,
      com.framehub.video.commerce.mapper.TradeMapper trades) {
    if (p.paymentProvider().equals("MOCK")) {
      Set<String> profiles = Set.of(env.getActiveProfiles());
      if (profiles.contains("prod")
          || profiles.contains("production")
          || (!profiles.contains("local") && !profiles.contains("test")))
        throw new IllegalStateException(
            "MOCK payments require local/test profile and cannot run in production");
      return new MockPaymentProvider(mock);
    }
    if (p.paymentProvider().equals("WECHAT"))
      return new WechatPaymentProvider(wechat, json, trades);
    if (!p.paymentProvider().equals("DISABLED"))
      throw new IllegalStateException("Unsupported payment provider: " + p.paymentProvider());
    return new DisabledPaymentProvider();
  }

  @Bean
  WithdrawalProvider withdrawalProvider(
      CommerceProperties p,
      Environment env,
      com.framehub.video.commerce.mapper.WithdrawalMapper db,
      com.framehub.video.commerce.domain.JsonData json) {
    if (p.withdrawalProvider().equals("MOCK")) {
      Set<String> profiles = Set.of(env.getActiveProfiles());
      if (profiles.contains("prod")
          || profiles.contains("production")
          || (!profiles.contains("local") && !profiles.contains("test")))
        throw new IllegalStateException(
            "MOCK withdrawals require local/test and cannot run in production");
      return new MockWithdrawalProvider(db, json);
    }
    if (!p.withdrawalProvider().equals("DISABLED"))
      throw new IllegalStateException("No live withdrawal adapter configured");
    return new DisabledWithdrawalProvider();
  }
}
