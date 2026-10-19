package com.framehub.video.commerce.controller;

import com.framehub.video.commerce.provider.PaymentProvider.BillRow;
import com.framehub.video.commerce.service.ReconciliationService;
import com.framehub.video.common.ApiResponse;
import com.framehub.video.security.Identity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class ReconciliationController {
  private final ReconciliationService service;

  public ReconciliationController(ReconciliationService service) {
    this.service = service;
  }

  public record Run(@NotNull LocalDate day) {}

  public record Fixture(@NotNull LocalDate day, @NotNull @Size(max = 100000) List<BillRow> rows) {}

  public record Resolution(@NotBlank @Size(max = 500) String resolution) {}

  @PostMapping("/admin/reconciliation/run")
  public ApiResponse<?> run(@AuthenticationPrincipal Identity u, @Valid @RequestBody Run r) {
    return ApiResponse.ok(service.channel(u.id(), r.day()));
  }

  @PostMapping("/admin/reconciliation/fixture")
  public ApiResponse<?> fixture(
      @AuthenticationPrincipal Identity u, @Valid @RequestBody Fixture r) {
    return ApiResponse.ok(service.fixture(u.id(), r.day(), r.rows()));
  }

  @GetMapping("/admin/reconciliation/batches")
  public ApiResponse<?> batches(
      @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
    return ApiResponse.ok(service.batches(page, size));
  }

  @GetMapping("/admin/reconciliation/batches/{id}/differences")
  public ApiResponse<?> differences(@PathVariable String id) {
    return ApiResponse.ok(service.differences(id));
  }

  @PostMapping("/admin/reconciliation/differences/{id}/acknowledge")
  public ApiResponse<?> ack(
      @AuthenticationPrincipal Identity u,
      @PathVariable String id,
      @Valid @RequestBody Resolution r) {
    service.acknowledge(u.id(), id, r.resolution());
    return ApiResponse.ok(null);
  }
}
