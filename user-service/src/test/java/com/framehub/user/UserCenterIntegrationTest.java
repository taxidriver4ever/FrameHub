package com.framehub.user;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import com.framehub.user.auth.service.MailSender;
import com.framehub.user.common.*;
import com.framehub.user.security.*;
import io.minio.*;
import jakarta.servlet.http.Cookie;
import java.io.ByteArrayInputStream;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.*;
import org.testcontainers.containers.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.junit.jupiter.Container;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
@Import(UserCenterIntegrationTest.MailConfig.class)
class UserCenterIntegrationTest {
  @Container static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

  @Container
  static GenericContainer<?> minio =
      new GenericContainer<>("coollabsio/minio:RELEASE.2025-04-22T22-12-26Z@sha256:a4938f37f1be1841b8e7b627ad0207b265345fd0d063e42d7410c78af0e63e68")
          .withEnv("MINIO_ROOT_USER", "test-access")
          .withEnv("MINIO_ROOT_PASSWORD", "test-secret-12345")
          .withCommand("server /data")
          .withExposedPorts(9000);

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", mysql::getJdbcUrl);
    r.add("spring.datasource.username", mysql::getUsername);
    r.add("spring.datasource.password", mysql::getPassword);
    r.add("spring.data.redis.host", redis::getHost);
    r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    r.add("spring.data.redis.password", () -> "");
    r.add("spring.mail.host", () -> "localhost");
    r.add("app.jwt-secret", () -> Base64.getEncoder().encodeToString(new byte[32]));
    r.add("app.code-secret", () -> "test-only-code-secret-with-32-bytes");
    r.add("app.worker-id", () -> 1);
    r.add("app.allowed-origins", () -> "http://localhost:3000");
    r.add("app.mail-from", () -> "noreply@test.local");
    r.add("app.reset-url", () -> "http://localhost:3000/reset");
    r.add(
        "app.minio-endpoint", () -> "http://" + minio.getHost() + ":" + minio.getMappedPort(9000));
    r.add(
        "app.minio-internal-endpoint",
        () -> "http://" + minio.getHost() + ":" + minio.getMappedPort(9000));
    r.add("app.minio-access-key", () -> "test-access");
    r.add("app.minio-secret-key", () -> "test-secret-12345");
  }

  static class TestMail implements MailSender {
    final Map<String, String> messages = new ConcurrentHashMap<>();

    public void send(String to, String subject, String text) {
      messages.put(to, text);
    }
  }

  @TestConfiguration
  static class MailConfig {
    @Bean
    @Primary
    TestMail testMail() {
      return new TestMail();
    }
  }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired TestMail mail;
  @Autowired RedisState state;
  @Autowired StringRedisTemplate template;
  @Autowired MinioClient minioClient;
  @Autowired JwtService jwt;
  @Autowired SessionService sessions;

  @BeforeEach
  void setup() throws Exception {
    Set<String> keys = template.keys("fh:user:rate:*");
    if (keys != null && !keys.isEmpty()) template.delete(keys);
    if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket("avatars").build()))
      minioClient.makeBucket(MakeBucketArgs.builder().bucket("avatars").build());
  }

  String email() {
    return UUID.randomUUID() + "@example.com";
  }

  String body(Object value) throws Exception {
    return json.writeValueAsString(value);
  }

  JsonNode data(MvcResult result) throws Exception {
    return json.readTree(result.getResponse().getContentAsString()).get("data");
  }

  String code(String email) throws Exception {
    mvc.perform(
            post("/auth/email-code")
                .contentType("application/json")
                .content(body(Map.of("email", email, "scene", "REGISTER"))))
        .andExpect(status().isOk());
    return java.util.regex.Pattern.compile("[0-9]{6}")
        .matcher(mail.messages.get(email))
        .results()
        .findFirst()
        .orElseThrow()
        .group();
  }

  MvcResult register(String email, String code) throws Exception {
    return mvc.perform(
            post("/auth/register")
                .contentType("application/json")
                .content(
                    body(
                        Map.of(
                            "email",
                            email,
                            "password",
                            "Password123!",
                            "verificationCode",
                            code,
                            "nickname",
                            "创作者"))))
        .andReturn();
  }

  String account() throws Exception {
    String e = email();
    assertThat(register(e, code(e)).getResponse().getStatus()).isEqualTo(201);
    return e;
  }

  MvcResult login(String email, String password) throws Exception {
    return mvc.perform(
            post("/auth/login")
                .header("Origin", "http://localhost:3000")
                .contentType("application/json")
                .content(body(Map.of("email", email, "password", password))))
        .andReturn();
  }

  String token(MvcResult r) throws Exception {
    return data(r).get("accessToken").asText();
  }

  Cookie cookie(MvcResult r) {
    return r.getResponse().getCookie("refresh_token");
  }

  MvcResult refresh(Cookie cookie) throws Exception {
    return mvc.perform(
            post("/auth/refresh").header("Origin", "http://localhost:3000").cookie(cookie))
        .andReturn();
  }

  @Test
  void registrationAndDuplicateEmail() throws Exception {
    String e = email();
    var result = register(e, code(e));
    assertThat(result.getResponse().getStatus()).isEqualTo(201);
    assertThat(data(result).get("userId").isTextual()).isTrue();
    assertThat(data(result).get("roles").get(0).asText()).isEqualTo("BUYER");
    assertThat(result.getResponse().getCookie("refresh_token")).isNull();
    state.delete("code-cooldown:" + Secrets.hash(e));
    assertThat(register(e, code(e)).getResponse().getStatus()).isEqualTo(409);
  }

  @Test
  void codeWrongExpiredReusedAndAttempts() throws Exception {
    String e = email(), c = code(e);
    assertThat(register(e, "999999".equals(c) ? "000000" : "999999").getResponse().getStatus())
        .isEqualTo(400);
    assertThat(register(e, c).getResponse().getStatus()).isEqualTo(201);
    assertThat(register(e, c).getResponse().getStatus()).isEqualTo(400);
    String other = email(), expired = code(other);
    state.delete("code:" + Secrets.hash(other));
    assertThat(register(other, expired).getResponse().getStatus()).isEqualTo(400);
    String limited = email(), valid = code(limited);
    for (int i = 0; i < 6; i++) register(limited, "999999".equals(valid) ? "000000" : "999999");
    assertThat(register(limited, valid).getResponse().getStatus()).isEqualTo(400);
  }

  @Test
  void emailCooldownAndLoginLimits() throws Exception {
    String e = account();
    mvc.perform(
            post("/auth/email-code")
                .contentType("application/json")
                .content(body(Map.of("email", e, "scene", "REGISTER"))))
        .andExpect(status().isTooManyRequests());
    for (int i = 0; i < 10; i++)
      assertThat(login(e, "incorrect123").getResponse().getStatus()).isEqualTo(401);
    assertThat(login(e, "Password123!").getResponse().getStatus()).isEqualTo(429);
  }

  @Test
  void loginCookieAndWrongPassword() throws Exception {
    String e = account();
    assertThat(login(e, "wrong123456").getResponse().getStatus()).isEqualTo(401);
    var result = login(e, "Password123!");
    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    assertThat(cookie(result).isHttpOnly()).isTrue();
    assertThat(cookie(result).getSecure()).isTrue();
    assertThat(cookie(result).getPath()).isEqualTo("/auth");
  }

  @Test
  void refreshRotationReplayRevokesFamily() throws Exception {
    var original = login(account(), "Password123!");
    var rotated = refresh(cookie(original));
    assertThat(rotated.getResponse().getStatus()).isEqualTo(200);
    assertThat(cookie(rotated).getValue()).isNotEqualTo(cookie(original).getValue());
    assertThat(refresh(cookie(original)).getResponse().getStatus()).isEqualTo(401);
    assertThat(refresh(cookie(rotated)).getResponse().getStatus()).isEqualTo(401);
    mvc.perform(get("/users/me").header("Authorization", "Bearer " + token(rotated)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void concurrentRotationHasOneWinner() throws Exception {
    var original = sessions.create(42, 0);
    try (var executor = Executors.newFixedThreadPool(2)) {
      CountDownLatch start = new CountDownLatch(1);
      Callable<Boolean> task =
          () -> {
            start.await();
            try {
              sessions.rotate(original.token(), original.session());
              return true;
            } catch (BusinessException e) {
              return false;
            }
          };
      var a = executor.submit(task);
      var b = executor.submit(task);
      start.countDown();
      assertThat(List.of(a.get(), b.get())).containsExactlyInAnyOrder(true, false);
      assertThat(sessions.active(original.session().family())).isFalse();
    }
  }

  @Test
  void logoutRevokesJtiAndRefresh() throws Exception {
    var logged = login(account(), "Password123!");
    String access = token(logged);
    mvc.perform(
            post("/auth/logout")
                .header("Origin", "http://localhost:3000")
                .header("Authorization", "Bearer " + access)
                .cookie(cookie(logged)))
        .andExpect(status().isOk())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie()
                .maxAge("refresh_token", 0));
    assertThat(state.get("jti:" + jwt.verify(access).jti())).isEqualTo("1");
    mvc.perform(get("/users/me").header("Authorization", "Bearer " + access))
        .andExpect(status().isUnauthorized());
    assertThat(refresh(cookie(logged)).getResponse().getStatus()).isEqualTo(401);
  }

  @Test
  void anonymousAndNonCreatorDenied() throws Exception {
    mvc.perform(get("/users/me")).andExpect(status().isUnauthorized());
    mvc.perform(get("/creators/me")).andExpect(status().isUnauthorized());
    var logged = login(account(), "Password123!");
    mvc.perform(get("/creators/me").header("Authorization", "Bearer " + token(logged)))
        .andExpect(status().isForbidden());
    mvc.perform(
            patch("/creators/me")
                .header("Authorization", "Bearer " + token(logged))
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void activateIdempotentlyAndOldJwtSeesRole() throws Exception {
    var logged = login(account(), "Password123!");
    String auth = "Bearer " + token(logged);
    var first =
        mvc.perform(post("/creators/me/activate").header("Authorization", auth))
            .andExpect(status().isOk())
            .andReturn();
    var second =
        mvc.perform(post("/creators/me/activate").header("Authorization", auth))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(data(first).get("creatorId")).isEqualTo(data(second).get("creatorId"));
    mvc.perform(get("/creators/me").header("Authorization", auth)).andExpect(status().isOk());
    mvc.perform(
            patch("/creators/me")
                .header("Authorization", auth)
                .contentType("application/json")
                .content("{\"bio\":\"hello\"}"))
        .andExpect(status().isOk());
    mvc.perform(get("/creators/" + data(first).get("creatorId").asText()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.email").doesNotExist())
        .andExpect(jsonPath("$.data.status").doesNotExist());
    mvc.perform(get("/users/me").header("Authorization", auth))
        .andExpect(jsonPath("$.data.roles.length()").value(2));
  }

  @Test
  void profilePatchAndOwnership() throws Exception {
    var logged = login(account(), "Password123!");
    String auth = "Bearer " + token(logged);
    mvc.perform(
            patch("/users/me")
                .header("Authorization", auth)
                .contentType("application/json")
                .content("{\"bio\":\"hello\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.nickname").value("创作者"));
    mvc.perform(
            patch("/users/me")
                .header("Authorization", auth)
                .contentType("application/json")
                .content("{\"bio\":null}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.bio").isEmpty());
    mvc.perform(
            patch("/users/me")
                .header("Authorization", auth)
                .contentType("application/json")
                .content("{\"userId\":\"123\",\"roles\":[\"CREATOR\"]}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            patch("/users/123")
                .header("Authorization", auth)
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void passwordChangeRevokesAllSessions() throws Exception {
    String e = account();
    var a = login(e, "Password123!");
    var b = login(e, "Password123!");
    mvc.perform(
            put("/users/me/password")
                .header("Authorization", "Bearer " + token(a))
                .contentType("application/json")
                .content(
                    "{\"currentPassword\":\"Password123!\",\"newPassword\":\"NewPassword123!\"}"))
        .andExpect(status().isOk());
    for (var r : List.of(a, b)) {
      mvc.perform(get("/users/me").header("Authorization", "Bearer " + token(r)))
          .andExpect(status().isUnauthorized());
      assertThat(refresh(cookie(r)).getResponse().getStatus()).isEqualTo(401);
    }
    assertThat(login(e, "NewPassword123!").getResponse().getStatus()).isEqualTo(200);
  }

  @Test
  void forgotResetSingleUseAndInvalidatesAllSessions() throws Exception {
    String e = account();
    var logged = login(e, "Password123!");
    var existing =
        mvc.perform(
                post("/auth/password/forgot")
                    .contentType("application/json")
                    .content(body(Map.of("email", e))))
            .andExpect(status().isOk())
            .andReturn();
    var missing =
        mvc.perform(
                post("/auth/password/forgot")
                    .contentType("application/json")
                    .content(body(Map.of("email", email()))))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(existing.getResponse().getContentAsString())
        .isEqualTo(missing.getResponse().getContentAsString());
    String reset = mail.messages.get(e).split("token=")[1].split("\\n")[0];
    String payload = body(Map.of("resetToken", reset, "newPassword", "NewPassword123!"));
    mvc.perform(post("/auth/password/reset").contentType("application/json").content(payload))
        .andExpect(status().isOk());
    mvc.perform(post("/auth/password/reset").contentType("application/json").content(payload))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/users/me").header("Authorization", "Bearer " + token(logged)))
        .andExpect(status().isUnauthorized());
    assertThat(refresh(cookie(logged)).getResponse().getStatus()).isEqualTo(401);
  }

  @Test
  void originProtection() throws Exception {
    mvc.perform(post("/auth/refresh")).andExpect(status().isForbidden());
    mvc.perform(
            post("/auth/login")
                .header("Origin", "https://evil.example")
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void avatarUploadConfirmOwnershipTypeAndSize() throws Exception {
    var logged = login(account(), "Password123!");
    String auth = "Bearer " + token(logged);
    mvc.perform(post("/users/me/avatar/upload-url").contentType("application/json").content("{}"))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/users/me/avatar/upload-url")
                .header("Authorization", auth)
                .contentType("application/json")
                .content(
                    "{\"filename\":\"x.svg\",\"contentType\":\"image/svg+xml\",\"sizeBytes\":32}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/users/me/avatar/upload-url")
                .header("Authorization", auth)
                .contentType("application/json")
                .content(
                    "{\"filename\":\"x.png\",\"contentType\":\"image/png\",\"sizeBytes\":2097153}"))
        .andExpect(status().isBadRequest());
    byte[] png =
        Base64.getDecoder()
            .decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a3ioAAAAASUVORK5CYII=");
    var upload =
        mvc.perform(
                post("/users/me/avatar/upload-url")
                    .header("Authorization", auth)
                    .contentType("application/json")
                    .content(
                        body(
                            Map.of(
                                "filename",
                                "../../unsafe.png",
                                "contentType",
                                "image/png",
                                "sizeBytes",
                                png.length))))
            .andExpect(status().isOk())
            .andReturn();
    String key = data(upload).get("objectKey").asText();
    String payload = body(Map.of("objectKey", key));
    assertThat(key).doesNotContain("unsafe", "..");
    mvc.perform(
            put("/users/me/avatar")
                .header("Authorization", auth)
                .contentType("application/json")
                .content(payload))
        .andExpect(status().isBadRequest());
    String other = "Bearer " + token(login(account(), "Password123!"));
    mvc.perform(
            put("/users/me/avatar")
                .header("Authorization", other)
                .contentType("application/json")
                .content(payload))
        .andExpect(status().isForbidden());
    minioClient.putObject(
        PutObjectArgs.builder().bucket("avatars").object(key).stream(
                new ByteArrayInputStream(new byte[png.length]), png.length, -1)
            .contentType("image/png")
            .build());
    mvc.perform(
            put("/users/me/avatar")
                .header("Authorization", auth)
                .contentType("application/json")
                .content(payload))
        .andExpect(status().isBadRequest());
    minioClient.putObject(
        PutObjectArgs.builder().bucket("avatars").object(key).stream(
                new ByteArrayInputStream(png), png.length, -1)
            .contentType("image/png")
            .build());
    mvc.perform(
            put("/users/me/avatar")
                .header("Authorization", auth)
                .contentType("application/json")
                .content(payload))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.avatarUrl").isString());
  }
}
