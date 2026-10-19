package com.framehub.gateway;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class TrafficLimiterIntegrationTest {
  @Container
  static GenericContainer<?> redis = new GenericContainer<>("redis:7.4").withExposedPorts(6379);

  static final DisposableServer downstream =
      HttpServer.create()
          .port(0)
          .handle(
              (req, res) -> {
                if (req.uri().equals("/internal/auth/identity")) {
                  if (!"Bearer valid".equals(req.requestHeaders().get("Authorization")))
                    return res.status(401).send();
                  return res.header("Content-Type", "application/json")
                      .sendString(Mono.just("{\"data\":{\"userId\":\"123\"}}"));
                }
                return res.sendString(Mono.just("ok"));
              })
          .bindNow();

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("USER_SERVICE_URL", () -> "http://localhost:" + downstream.port());
    r.add("VIDEO_SERVICE_URL", () -> "http://localhost:" + downstream.port());
    r.add("ALLOWED_ORIGINS", () -> "http://localhost:3000");
    r.add("spring.data.redis.host", redis::getHost);
    r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    r.add("app.rate-limit.enabled", () -> true);
    r.add("app.rate-limit.interface-rate", () -> 1);
    r.add("app.rate-limit.interface-burst", () -> 2);
  }

  @Autowired WebTestClient client;
  @Autowired ReactiveStringRedisTemplate template;
  @Autowired TrafficLimiter limiter;

  @AfterAll
  static void stop() {
    downstream.disposeNow();
  }

  @Test
  void authenticatedUserCannotEvadeLimitByChangingResourceId() {
    String first = "/orders/11111111-1111-1111-1111-111111111111",
        second = "/orders/22222222-2222-2222-2222-222222222222";
    client
        .get()
        .uri(first)
        .header("Authorization", "Bearer valid")
        .exchange()
        .expectStatus()
        .isOk();
    client
        .get()
        .uri(second)
        .header("Authorization", "Bearer valid")
        .exchange()
        .expectStatus()
        .isOk();
    client
        .get()
        .uri(first)
        .header("Authorization", "Bearer valid")
        .exchange()
        .expectStatus()
        .isEqualTo(429);
    client
        .get()
        .uri("/materials/11111111-1111-1111-1111-111111111111")
        .header("Authorization", "Bearer valid")
        .exchange()
        .expectStatus()
        .isOk();
  }

  @Test
  void invalidTokenCannotChooseUserBucket() {
    client
        .get()
        .uri("/orders")
        .header("Authorization", "Bearer fake")
        .exchange()
        .expectStatus()
        .isUnauthorized();
  }

  @Test
  void atomicBucketEnforcesConcurrentCapacity() {
    String key = java.util.UUID.randomUUID().toString();
    long allowed =
        reactor.core.publisher.Flux.range(0, 40)
            .flatMap(i -> limiter.allowed(key, 1, 5), 40)
            .filter(Boolean::booleanValue)
            .count()
            .block(Duration.ofSeconds(5));
    assertThat(allowed).isEqualTo(5);
  }

  @Test
  void preflightDoesNotConsumeAuthenticationBudget() {
    client
        .options()
        .uri("/orders")
        .header("Origin", "http://localhost:3000")
        .header("Access-Control-Request-Method", "POST")
        .header("Access-Control-Request-Headers", "authorization,content-type")
        .exchange()
        .expectStatus()
        .isOk();
  }
}
