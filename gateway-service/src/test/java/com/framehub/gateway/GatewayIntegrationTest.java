package com.framehub.gateway;

import static org.assertj.core.api.Assertions.*;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayIntegrationTest {
  static final AtomicReference<String> clientIp = new AtomicReference<>();
  static final DisposableServer downstream =
      HttpServer.create()
          .port(0)
          .handle(
              (req, res) -> {
                clientIp.set(req.requestHeaders().get("X-FrameHub-Client-IP"));
                res.header("Content-Type", "text/plain");
                if (req.uri().equals("/auth/login"))
                  res.header(
                      "Set-Cookie", "refresh_token=test; Path=/auth; HttpOnly; SameSite=Strict");
                if (req.uri().equals("/users/me"))
                  return res.sendString(
                      Mono.just(
                          req.requestHeaders().get("Authorization", "")
                              + "|"
                              + req.requestHeaders().get("Cookie", "")));
                return res.sendString(Mono.just(req.method().name() + " " + req.uri()));
              })
          .bindNow();

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("app.rate-limit.enabled", () -> false);
    r.add("USER_SERVICE_URL", () -> "http://127.0.0.1:" + downstream.port());
    r.add("VIDEO_SERVICE_URL", () -> "http://127.0.0.1:" + downstream.port());
    r.add("ALLOWED_ORIGINS", () -> "http://localhost:3000");
  }

  @Autowired WebTestClient client;

  @AfterAll
  static void stop() {
    downstream.disposeNow();
  }

  @Test
  void forwardsAllPathsAndMethodsWithoutRewrite() {
    for (String path :
        new String[] {
          "/auth/register",
          "/materials",
          "/orders",
          "/creators/me/materials",
          "/creators/me/activate",
          "/creators/123?x=1",
          "/api/v1/videos/uploads/init",
          "/api/v1/videos/me?page=1"
        })
      client
          .post()
          .uri(path)
          .bodyValue("{}")
          .exchange()
          .expectStatus()
          .isOk()
          .expectBody(String.class)
          .isEqualTo("POST " + path);
    client
        .patch()
        .uri("/creators/me")
        .bodyValue("{}")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .isEqualTo("PATCH /creators/me");
  }

  @Test
  void forwardsAuthCookiesAndSanitizesClientIp() {
    client
        .get()
        .uri("/users/me")
        .header("Authorization", "Bearer opaque")
        .header("X-FrameHub-Client-IP", "spoof")
        .cookie("refresh_token", "opaque")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .value(v -> assertThat(v).contains("Bearer opaque", "refresh_token=opaque"));
    assertThat(clientIp.get()).isNotEqualTo("spoof");
  }

  @Test
  void loginCookieAndCorsHaveSingleOrigin() {
    client
        .post()
        .uri("/auth/login")
        .header("Origin", "http://localhost:3000")
        .bodyValue("{}")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .valueEquals("Access-Control-Allow-Origin", "http://localhost:3000")
        .expectHeader()
        .valueEquals("Access-Control-Allow-Credentials", "true")
        .expectCookie()
        .httpOnly("refresh_token", true);
  }

  @Test
  void optionsPreflightNeedsNoAuthAndUnknownRouteWorks() {
    for (String path :
        new String[] {
          "/users/me",
          "/auth/register",
          "/materials",
          "/orders",
          "/creators/me/materials",
          "/not-yet-routed"
        })
      client
          .options()
          .uri(path)
          .header("Origin", "http://localhost:3000")
          .header("Access-Control-Request-Method", "POST")
          .header("Access-Control-Request-Headers", "content-type,authorization")
          .exchange()
          .expectStatus()
          .isOk()
          .expectHeader()
          .valueEquals("Access-Control-Allow-Origin", "http://localhost:3000")
          .expectHeader()
          .valueEquals("Access-Control-Allow-Credentials", "true");
  }

  @Test
  void rejectsUntrustedOrigins() {
    client
        .options()
        .uri("/auth/login")
        .header("Origin", "https://evil.example")
        .header("Access-Control-Request-Method", "POST")
        .exchange()
        .expectStatus()
        .isForbidden();
  }

  @Test
  void noUnconfiguredServiceRoute() {
    client.get().uri("/unconfigured-service/me").exchange().expectStatus().isNotFound();
  }
}
