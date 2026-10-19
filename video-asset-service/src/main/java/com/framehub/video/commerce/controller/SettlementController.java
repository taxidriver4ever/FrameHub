package com.framehub.video.commerce.controller;

import com.framehub.video.commerce.service.*;
import com.framehub.video.common.ApiResponse;
import com.framehub.video.security.Identity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class SettlementController {
  private final LedgerService ledger;
  private final EntitlementService entitlements;

  public SettlementController(LedgerService ledger, EntitlementService entitlements) {
    this.ledger = ledger;
    this.entitlements = entitlements;
  }

  @GetMapping("/entitlements")
  public ApiResponse<?> mine(
      @AuthenticationPrincipal Identity u,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    return ApiResponse.ok(entitlements.mine(u.id(), page, size));
  }

  @PostMapping("/entitlements/{id}/download")
  public ApiResponse<?> download(@AuthenticationPrincipal Identity u, @PathVariable String id) {
    return ApiResponse.ok(entitlements.download(u.id(), id));
  }

  @GetMapping("/earnings/balance")
  public ApiResponse<?> balance(@AuthenticationPrincipal Identity u) {
    return ApiResponse.ok(ledger.balances(u.id()));
  }

  @GetMapping("/earnings/entries")
  public ApiResponse<?> entries(
      @AuthenticationPrincipal Identity u,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    return ApiResponse.ok(ledger.history(u.id(), page, size));
  }

  @GetMapping("/admin/ledger/verify")
  public ApiResponse<?> verify() {
    return ApiResponse.ok(ledger.verify());
  }
}
