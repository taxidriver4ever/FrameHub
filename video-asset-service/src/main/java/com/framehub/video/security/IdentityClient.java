package com.framehub.video.security;

import com.framehub.video.common.ApiException;
import com.framehub.video.config.VideoProperties;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class IdentityClient {
  private final RestClient client;

  public record Response(int code, String message, Identity data) {}

  public IdentityClient(VideoProperties p) {
    var factory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(5));
    client = RestClient.builder().baseUrl(p.identityUrl()).requestFactory(factory).build();
  }

  public Identity verify(String bearer) {
    try {
      Response response =
          client
              .get()
              .uri("/internal/auth/identity")
              .header("Authorization", bearer)
              .retrieve()
              .body(Response.class);
      if (response == null || response.data() == null) throw new ApiException(401, "登录失效");
      return response.data();
    } catch (org.springframework.web.client.HttpClientErrorException e) {
      throw new ApiException(e.getStatusCode().value() == 403 ? 403 : 401, "登录失效或无权限");
    } catch (ApiException e) {
      throw e;
    } catch (Exception e) {
      throw new ApiException(503, "认证服务不可用");
    }
  }
}
