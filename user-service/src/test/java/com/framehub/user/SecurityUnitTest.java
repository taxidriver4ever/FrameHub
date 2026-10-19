package com.framehub.user;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.framehub.user.common.*;
import com.framehub.user.config.AppProperties;
import com.framehub.user.security.*;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.time.Instant;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;

class SecurityUnitTest {
  AppProperties properties() {
    AppProperties p = mock(AppProperties.class);
    when(p.jwtSecret()).thenReturn(Base64.getEncoder().encodeToString(new byte[32]));
    when(p.issuer()).thenReturn("framehub-user");
    when(p.audience()).thenReturn("framehub");
    when(p.accessSeconds()).thenReturn(900L);
    when(p.workerId()).thenReturn(7);
    return p;
  }

  String signed(Instant expiry, String audience) {
    var encoder =
        new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(new byte[32], "HmacSHA256")));
    return encoder
        .encode(
            JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder()
                    .issuer("framehub-user")
                    .audience(List.of(audience))
                    .subject("123")
                    .id("jti")
                    .issuedAt(expiry.minusSeconds(900))
                    .expiresAt(expiry)
                    .claim("ver", 0L)
                    .claim("sid", "family")
                    .build()))
        .getTokenValue();
  }

  @Test
  void jwtRejectsExpiredWrongAudienceAndTamperedSignature() {
    JwtService service = new JwtService(properties());
    assertThatThrownBy(() -> service.verify(signed(Instant.now().minusSeconds(1), "framehub")))
        .isInstanceOf(JwtException.class);
    assertThatThrownBy(() -> service.verify(signed(Instant.now().plusSeconds(60), "wrong")))
        .isInstanceOf(JwtException.class);
    String valid = service.issue(new SessionService.Session(123, 0, "family"));
    assertThat(service.verify(valid).id()).isEqualTo(123);
    String[] parts = valid.split("\\.");
    assertThatThrownBy(
            () ->
                service.verify(
                    parts[0] + "." + parts[1] + ".AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"))
        .isInstanceOf(JwtException.class);
  }

  @Test
  void snowflakeUniqueAndFailsWhenLeaseLost() {
    WorkerLease lease = mock(WorkerLease.class);
    Snowflake snowflake = new Snowflake(properties(), lease);
    Set<Long> ids = new HashSet<>();
    for (int i = 0; i < 10000; i++) assertThat(ids.add(snowflake.next())).isTrue();
    doThrow(new IllegalStateException("lease lost")).when(lease).check();
    assertThatThrownBy(snowflake::next).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void passwordByteLimitAndMarkupRejected() {
    assertThatThrownBy(() -> InputRules.password("密".repeat(30) + "abc123"))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> InputRules.text("<script>", 64, false))
        .isInstanceOf(BusinessException.class);
  }
}
