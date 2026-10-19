package com.framehub.gateway;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.context.annotation.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.*;

@Configuration
public class GatewayConfig {
  @Bean
  CorsWebFilter cors(@Value("${app.allowed-origins}") List<String> origins) {
    if (origins.isEmpty() || origins.stream().anyMatch(o -> o.isBlank() || o.contains("*")))
      throw new IllegalArgumentException("Explicit allowed origins required");
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowedOrigins(origins);
    config.setAllowCredentials(true);
    config.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
    config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
    config.setMaxAge(3600L);
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", config);
    return new CorsWebFilter(source);
  }

  /**
   * Overwrite rather than append client-supplied forwarding headers at this public trust boundary.
   */
  @Bean
  @Order(Ordered.HIGHEST_PRECEDENCE)
  GlobalFilter clientAddress() {
    return (exchange, chain) -> {
      String ip =
          exchange.getRequest().getRemoteAddress() == null
              ? "unknown"
              : exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();
      var request =
          exchange
              .getRequest()
              .mutate()
              .headers(
                  h -> {
                    h.remove("Forwarded");
                    h.remove("X-Forwarded-For");
                    h.remove("X-Real-IP");
                    h.set("X-FrameHub-Client-IP", ip);
                  })
              .build();
      return chain.filter(exchange.mutate().request(request).build());
    };
  }
}
