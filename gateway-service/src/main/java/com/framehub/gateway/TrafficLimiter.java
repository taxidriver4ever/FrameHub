package com.framehub.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.gateway.filter.*;
import org.springframework.core.Ordered;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
@ConditionalOnProperty(name = "app.rate-limit.enabled", havingValue = "true", matchIfMissing = true)
public class TrafficLimiter implements GlobalFilter, Ordered {
  private final ReactiveStringRedisTemplate redis;
  private final WebClient identity;
  private final int userRate, userBurst, interfaceRate, interfaceBurst;
  private final RedisScript<Long> script =
      RedisScript.of(new ClassPathResource("token-bucket.lua"), Long.class);

  public TrafficLimiter(
      ReactiveStringRedisTemplate redis,
      WebClient.Builder builder,
      @Value("${USER_SERVICE_URL:http://127.0.0.1:8081}") String url,
      @Value("${app.rate-limit.user-rate:20}") int userRate,
      @Value("${app.rate-limit.user-burst:40}") int userBurst,
      @Value("${app.rate-limit.interface-rate:5}") int interfaceRate,
      @Value("${app.rate-limit.interface-burst:10}") int interfaceBurst) {
    this.redis = redis;
    this.identity = builder.baseUrl(url).build();
    this.userRate = userRate;
    this.userBurst = userBurst;
    this.interfaceRate = interfaceRate;
    this.interfaceBurst = interfaceBurst;
    if (userRate < 1 || userBurst < 1 || interfaceRate < 1 || interfaceBurst < 1)
      throw new IllegalArgumentException("Rate limits must be positive");
  }

  public int getOrder() {
    return -90;
  }

  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    if (exchange.getRequest().getMethod() == HttpMethod.OPTIONS) return chain.filter(exchange);
    String ip =
        exchange.getRequest().getRemoteAddress() == null
            ? "unknown"
            : exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();
    String auth = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
    String path = exchange.getRequest().getPath().value();
    return allowed("ip:" + ip, 100, 200)
        .flatMap(
            ipAllowed -> {
              if (!ipAllowed) return Mono.error(new Rejected(429));
              Mono<String> subject;
              if (auth == null) subject = Mono.just("ip:" + ip);
              else
                subject =
                    identity
                        .get()
                        .uri("/internal/auth/identity")
                        .header(HttpHeaders.AUTHORIZATION, auth)
                        .retrieve()
                        .onStatus(
                            s -> s.value() == 401 || s.value() == 403,
                            r -> Mono.error(new Rejected(401)))
                        .bodyToMono(JsonNode.class)
                        .timeout(Duration.ofSeconds(3))
                        .flatMap(
                            n ->
                                n.path("data").path("userId").asText().matches("[0-9]+")
                                    ? Mono.just("user:" + n.path("data").path("userId").asText())
                                    : Mono.error(new Rejected(401)));
              return subject
                  .flatMap(
                      key ->
                          allowed("all:" + key, userRate, userBurst)
                              .flatMap(
                                  all ->
                                      !all
                                          ? Mono.error(new Rejected(429))
                                          : allowed(
                                              "api:"
                                                  + key
                                                  + ":"
                                                  + exchange.getRequest().getMethod()
                                                  + ":"
                                                  + template(path),
                                              interfaceRate,
                                              interfaceBurst)))
                  .flatMap(ok -> ok ? Mono.just(true) : Mono.error(new Rejected(429)));
            })
        .onErrorResume(
            e -> {
              int status = e instanceof Rejected r ? r.status : 503;
              exchange.getResponse().setStatusCode(HttpStatusCode.valueOf(status));
              exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
              if (status == 429) exchange.getResponse().getHeaders().set("Retry-After", "1");
              String body =
                  "{\"code\":"
                      + status
                      + ",\"message\":\""
                      + (status == 429 ? "请求过于频繁" : status == 401 ? "登录失效" : "限流或认证服务不可用")
                      + "\",\"data\":null}";
              return exchange
                  .getResponse()
                  .writeWith(
                      Mono.just(
                          exchange
                              .getResponse()
                              .bufferFactory()
                              .wrap(body.getBytes(StandardCharsets.UTF_8))))
                  .thenReturn(false);
            })
        .flatMap(ok -> ok ? chain.filter(exchange) : Mono.empty());
  }

  Mono<Boolean> allowed(String key, int rate, int capacity) {
    return redis
        .execute(script, List.of("framehub:limit:" + hash(key)), List.of("" + rate, "" + capacity))
        .single()
        .timeout(Duration.ofSeconds(2))
        .map(v -> v == 1);
  }

  static String template(String path) {
    return path.replaceAll(
            "(?i)/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(?=/|$)", "/{id}")
        .replaceAll("/[0-9]+(?=/|$)", "/{id}");
  }

  private static String hash(String s) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static class Rejected extends RuntimeException {
    final int status;

    Rejected(int status) {
      this.status = status;
    }
  }
}
