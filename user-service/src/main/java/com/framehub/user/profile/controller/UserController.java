package com.framehub.user.profile.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.framehub.user.common.ApiResponse;
import com.framehub.user.profile.dto.ProfileRequests.*;
import com.framehub.user.profile.service.UserService;
import com.framehub.user.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/users/me")
public class UserController {
  private final UserService service;

  public UserController(UserService service) {
    this.service = service;
  }

  @GetMapping
  public ApiResponse<?> get(@AuthenticationPrincipal CurrentUser u) {
    return ApiResponse.ok(service.get(u.id()));
  }

  @PatchMapping
  public ApiResponse<?> patch(@AuthenticationPrincipal CurrentUser u, @RequestBody JsonNode req) {
    return ApiResponse.ok(service.patch(u.id(), req));
  }

  @PutMapping("/password")
  public ApiResponse<?> password(
      @AuthenticationPrincipal CurrentUser u, @Valid @RequestBody Password req) {
    service.password(u, req);
    return ApiResponse.ok("密码已修改，请重新登录", null);
  }

  @PostMapping("/avatar/upload-url")
  public ApiResponse<?> upload(
      @AuthenticationPrincipal CurrentUser u, @Valid @RequestBody Upload req) throws Exception {
    return ApiResponse.ok(service.upload(u.id(), req));
  }

  @PutMapping("/avatar")
  public ApiResponse<?> avatar(
      @AuthenticationPrincipal CurrentUser u, @Valid @RequestBody Avatar req) throws Exception {
    return ApiResponse.ok(service.avatar(u.id(), req.objectKey()));
  }
}
