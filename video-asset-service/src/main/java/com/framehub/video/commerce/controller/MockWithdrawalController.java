package com.framehub.video.commerce.controller;

import com.framehub.video.commerce.dto.CommerceRequests.MockOutcome;
import com.framehub.video.commerce.service.WithdrawalService;
import com.framehub.video.common.ApiResponse;
import com.framehub.video.security.Identity;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("local | test")
@ConditionalOnProperty(name = "commerce.withdrawal-provider", havingValue = "MOCK")
public class MockWithdrawalController {
  private final WithdrawalService withdrawals;

  public MockWithdrawalController(WithdrawalService withdrawals) {
    this.withdrawals = withdrawals;
  }

  @PostMapping("/dev/withdrawals/{id}/complete")
  public ApiResponse<?> complete(
      @AuthenticationPrincipal Identity u,
      @PathVariable String id,
      @Valid @RequestBody MockOutcome r) {
    return ApiResponse.ok(withdrawals.mockComplete(u.id(), id, r.success()));
  }
}
