package com.framehub.user.auth.controller;

import com.framehub.user.auth.dto.AuthRequests.*;
import com.framehub.user.auth.service.AuthService;
import com.framehub.user.common.ApiResponse;
import com.framehub.user.config.AppProperties;
import com.framehub.user.security.CurrentUser;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
public class AuthController {
  private final AuthService service;
  private final AppProperties p;

  public AuthController(AuthService service, AppProperties p) {
    this.service = service;
    this.p = p;
  }

  @PostMapping("/register")
  @ResponseStatus(HttpStatus.CREATED)
  public ApiResponse<?> register(@Valid @RequestBody Register req) {
    return ApiResponse.ok("注册成功", service.register(req));
  }

  @PostMapping("/login")
  public ApiResponse<?> login(
      @Valid @RequestBody Login req, HttpServletRequest request, HttpServletResponse response) {
    var result = service.login(req, clientIp(request));
    cookie(response, result.refreshToken(), p.refreshSeconds());
    return ApiResponse.ok("登录成功", result.body());
  }

  @PostMapping("/refresh")
  public ApiResponse<?> refresh(
      @CookieValue(name = "refresh_token", required = false) String token,
      HttpServletResponse response) {
    var result = service.refresh(token);
    cookie(response, result.refreshToken(), p.refreshSeconds());
    return ApiResponse.ok(result.body());
  }

  @PostMapping("/logout")
  public ApiResponse<?> logout(
      @AuthenticationPrincipal CurrentUser user, HttpServletResponse response) {
    service.logout(user);
    cookie(response, "", 0);
    return ApiResponse.ok(null);
  }

  @PostMapping("/email-code")
  public ApiResponse<?> code(@Valid @RequestBody EmailCode req, HttpServletRequest request) {
    service.emailCode(req, clientIp(request));
    return ApiResponse.ok("验证码已发送", null);
  }

  @PostMapping("/password/forgot")
  public ApiResponse<?> forgot(@Valid @RequestBody Forgot req, HttpServletRequest request) {
    service.forgot(req, clientIp(request));
    return ApiResponse.ok("如果邮箱已注册，将收到重置邮件", null);
  }

  @PostMapping("/password/reset")
  public ApiResponse<?> reset(
      @Valid @RequestBody Reset req, HttpServletRequest request, HttpServletResponse response) {
    service.reset(req, clientIp(request));
    cookie(response, "", 0);
    return ApiResponse.ok("密码已重置，请重新登录", null);
  }

  private String clientIp(HttpServletRequest request) {
    String ip = request.getHeader("X-FrameHub-Client-IP");
    return p.trustGateway() && ip != null && ip.length() <= 64 ? ip : request.getRemoteAddr();
  }

  private void cookie(HttpServletResponse response, String token, long seconds) {
    response.addHeader(
        HttpHeaders.SET_COOKIE,
        ResponseCookie.from("refresh_token", token)
            .httpOnly(true)
            .secure(p.cookieSecure())
            .sameSite("Strict")
            .path("/auth")
            .maxAge(seconds)
            .build()
            .toString());
  }
}
