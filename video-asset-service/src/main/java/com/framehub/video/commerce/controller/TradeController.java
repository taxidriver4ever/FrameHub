package com.framehub.video.commerce.controller;

import com.framehub.video.commerce.dto.CommerceRequests.*;
import com.framehub.video.commerce.service.*;
import com.framehub.video.common.ApiResponse;
import com.framehub.video.security.Identity;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class TradeController {
  private final OrderService orders;
  private final PaymentService payments;

  public TradeController(OrderService orders, PaymentService payments) {
    this.orders = orders;
    this.payments = payments;
  }

  @PostMapping("/orders")
  public ApiResponse<?> create(
      @AuthenticationPrincipal Identity u, @Valid @RequestBody Purchase r) {
    return ApiResponse.ok(orders.purchase(u.id(), r));
  }

  @GetMapping("/orders")
  public ApiResponse<?> mine(
      @AuthenticationPrincipal Identity u,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    return ApiResponse.ok(orders.mine(u.id(), page, size));
  }

  @GetMapping("/orders/{id}")
  public ApiResponse<?> get(@AuthenticationPrincipal Identity u, @PathVariable String id) {
    return ApiResponse.ok(orders.get(u.id(), id));
  }

  @PostMapping("/orders/{id}/pay")
  public ApiResponse<?> pay(@AuthenticationPrincipal Identity u, @PathVariable String id) {
    return ApiResponse.ok(payments.pay(u.id(), id));
  }

  @PostMapping("/orders/{id}/cancel")
  public ApiResponse<?> cancel(@AuthenticationPrincipal Identity u, @PathVariable String id) {
    payments.close(u.id(), id, false);
    return ApiResponse.ok(orders.get(u.id(), id));
  }

  @PostMapping("/orders/{id}/refund")
  public ApiResponse<?> refund(
      @AuthenticationPrincipal Identity u,
      @PathVariable String id,
      @Valid @RequestBody RefundRequest r) {
    return ApiResponse.ok(payments.refund(u.id(), id, r, false));
  }

  @GetMapping("/payments/{id}")
  public ApiResponse<?> payment(@AuthenticationPrincipal Identity u, @PathVariable String id) {
    return ApiResponse.ok(payments.query(u.id(), id));
  }

  @GetMapping("/admin/orders")
  public ApiResponse<?> all(
      @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
    return ApiResponse.ok(orders.all(page, size));
  }

  @PostMapping("/admin/orders/{id}/refund")
  public ApiResponse<?> adminRefund(
      @AuthenticationPrincipal Identity u,
      @PathVariable String id,
      @Valid @RequestBody RefundRequest r) {
    return ApiResponse.ok(payments.refund(u.id(), id, r, true));
  }
}
