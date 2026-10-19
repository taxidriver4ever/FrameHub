package com.framehub.video.commerce.controller;

import com.framehub.video.commerce.dto.CommerceRequests.*;
import com.framehub.video.commerce.service.WithdrawalService;
import com.framehub.video.common.ApiResponse;
import com.framehub.video.security.Identity;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class WithdrawalController {
  private final WithdrawalService withdrawals;

  public WithdrawalController(WithdrawalService withdrawals) {
    this.withdrawals = withdrawals;
  }

  @PostMapping("/withdrawals")
  public ApiResponse<?> create(
      @AuthenticationPrincipal Identity u, @Valid @RequestBody WithdrawalRequest r) {
    return ApiResponse.ok(withdrawals.create(u.id(), r));
  }

  @GetMapping("/withdrawals")
  public ApiResponse<?> mine(
      @AuthenticationPrincipal Identity u,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    return ApiResponse.ok(withdrawals.mine(u.id(), page, size));
  }

  @GetMapping("/withdrawals/{id}")
  public ApiResponse<?> get(@AuthenticationPrincipal Identity u, @PathVariable String id) {
    return ApiResponse.ok(withdrawals.get(u.id(), id));
  }

  @PutMapping("/withdrawals/account")
  public ApiResponse<?> account(
      @AuthenticationPrincipal Identity u, @Valid @RequestBody WithdrawalAccount r) {
    withdrawals.saveAccount(u.id(), r);
    return ApiResponse.ok(withdrawals.account(u.id()));
  }

  @GetMapping("/withdrawals/account")
  public ApiResponse<?> account(@AuthenticationPrincipal Identity u) {
    return ApiResponse.ok(withdrawals.account(u.id()));
  }
}
