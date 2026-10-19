package com.framehub.user.creator.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.framehub.user.common.ApiResponse;
import com.framehub.user.creator.service.CreatorService;
import com.framehub.user.security.CurrentUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/creators")
public class CreatorController {
  private final CreatorService service;

  public CreatorController(CreatorService service) {
    this.service = service;
  }

  @PostMapping("/me/activate")
  public ApiResponse<?> activate(@AuthenticationPrincipal CurrentUser u) {
    return ApiResponse.ok(service.activate(u.id()));
  }

  @GetMapping("/me")
  public ApiResponse<?> own(@AuthenticationPrincipal CurrentUser u) {
    return ApiResponse.ok(service.own(u.id()));
  }

  @PatchMapping("/me")
  public ApiResponse<?> patch(@AuthenticationPrincipal CurrentUser u, @RequestBody JsonNode req) {
    return ApiResponse.ok(service.patch(u.id(), req));
  }

  @GetMapping("/{creatorId}")
  public ApiResponse<?> homepage(@PathVariable long creatorId) {
    return ApiResponse.ok(service.homepage(creatorId));
  }
}
