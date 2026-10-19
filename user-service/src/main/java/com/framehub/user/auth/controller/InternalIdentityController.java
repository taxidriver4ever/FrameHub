package com.framehub.user.auth.controller;

import com.framehub.user.auth.service.IdentityService;
import com.framehub.user.common.ApiResponse;
import com.framehub.user.security.CurrentUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/auth")
public class InternalIdentityController {
  private final IdentityService service;

  public InternalIdentityController(IdentityService service) {
    this.service = service;
  }

  @GetMapping("/identity")
  public ApiResponse<?> identity(@AuthenticationPrincipal CurrentUser user) {
    return ApiResponse.ok(service.get(user.id()));
  }
}
