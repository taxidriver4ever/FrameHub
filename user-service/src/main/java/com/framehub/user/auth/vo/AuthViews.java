package com.framehub.user.auth.vo;

import java.util.List;

public final class AuthViews {
  private AuthViews() {}

  public record Registered(
      String userId, String email, String nickname, List<String> roles, String status) {}

  public record Summary(String userId, String nickname, List<String> roles) {}

  public record Login(String accessToken, String tokenType, long expiresIn, Summary user) {}
}
