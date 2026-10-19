package com.framehub.user.security;

import com.framehub.user.config.AppProperties;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.time.Instant;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

@Service
public class JwtService {
  private final JwtEncoder encoder;
  private final JwtDecoder decoder;
  private final AppProperties p;

  public JwtService(AppProperties p) {
    this.p = p;
    byte[] bytes = Base64.getDecoder().decode(p.jwtSecret());
    if (bytes.length < 32)
      throw new IllegalArgumentException("JWT secret must contain at least 32 random bytes");
    var key = new SecretKeySpec(bytes, "HmacSHA256");
    encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
    var d = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    d.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            new JwtTimestampValidator(java.time.Duration.ZERO),
            new JwtIssuerValidator(p.issuer()),
            jwt ->
                jwt.getAudience().contains(p.audience())
                        && jwt.getSubject() != null
                        && jwt.getId() != null
                        && jwt.getExpiresAt() != null
                        && jwt.getIssuedAt() != null
                        && jwt.hasClaim("ver")
                        && jwt.hasClaim("sid")
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"))));
    decoder = d;
  }

  public String issue(SessionService.Session s) {
    Instant now = Instant.now();
    return encoder
        .encode(
            JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder()
                    .issuer(p.issuer())
                    .audience(List.of(p.audience()))
                    .subject("" + s.userId())
                    .id(UUID.randomUUID().toString())
                    .issuedAt(now)
                    .expiresAt(now.plusSeconds(p.accessSeconds()))
                    .claim("ver", s.version())
                    .claim("sid", s.family())
                    .build()))
        .getTokenValue();
  }

  public CurrentUser verify(String token) {
    Jwt jwt = decoder.decode(token);
    return new CurrentUser(
        Long.parseLong(jwt.getSubject()),
        ((Number) jwt.getClaim("ver")).longValue(),
        jwt.getId(),
        jwt.getClaimAsString("sid"),
        jwt.getExpiresAt().getEpochSecond());
  }
}
