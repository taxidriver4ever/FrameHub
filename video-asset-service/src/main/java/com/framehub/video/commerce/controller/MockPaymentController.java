package com.framehub.video.commerce.controller;

import com.framehub.video.commerce.service.PaymentService;
import com.framehub.video.common.ApiResponse;
import com.framehub.video.security.Identity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("local | test")
@ConditionalOnProperty(name = "commerce.payment-provider", havingValue = "MOCK")
public class MockPaymentController {
  private final PaymentService payments;

  public MockPaymentController(PaymentService payments) {
    this.payments = payments;
  }

  @PostMapping("/dev/payments/{id}/succeed")
  public ApiResponse<?> succeed(@AuthenticationPrincipal Identity u, @PathVariable String id) {
    return ApiResponse.ok(payments.mockSuccess(u.id(), id));
  }
}
