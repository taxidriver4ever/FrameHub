package com.framehub.video.commerce.config;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

class CommerceConfigTest {
  @ParameterizedTest
  @ValueSource(strings = {"production", "prod", "staging", "local,production", "test,prod"})
  void refusesMockPaymentsAndWithdrawalsOutsideLocalTest(String profiles) {
    var environment = new MockEnvironment();
    environment.setActiveProfiles(profiles.split(","));
    var properties = new CommerceProperties("MOCK", "MOCK", false, 5, 10, null, null, "CNY");
    var config = new CommerceConfig();
    assertThatThrownBy(
            () -> config.paymentProvider(properties, environment, null, null, null, null))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> config.withdrawalProvider(properties, environment, null, null))
        .isInstanceOf(IllegalStateException.class);
  }
}
