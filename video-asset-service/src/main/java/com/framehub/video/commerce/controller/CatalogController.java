package com.framehub.video.commerce.controller;

import com.framehub.video.commerce.dto.CommerceRequests.*;
import com.framehub.video.commerce.service.*;
import com.framehub.video.common.ApiResponse;
import com.framehub.video.security.Identity;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class CatalogController {
  private final CatalogService catalog;
  private final OrderService orders;

  public CatalogController(CatalogService catalog, OrderService orders) {
    this.catalog = catalog;
    this.orders = orders;
  }

  @PostMapping("/materials")
  public ApiResponse<?> create(@AuthenticationPrincipal Identity u, @Valid @RequestBody Draft r) {
    return ApiResponse.ok(catalog.create(u.id(), r));
  }

  @GetMapping("/materials/{id}")
  public ApiResponse<?> get(@AuthenticationPrincipal Identity u, @PathVariable String id) {
    return ApiResponse.ok(catalog.get(u.id(), id));
  }

  @PatchMapping("/materials/{id}")
  public ApiResponse<?> edit(
      @AuthenticationPrincipal Identity u, @PathVariable String id, @Valid @RequestBody Edit r) {
    return ApiResponse.ok(catalog.edit(u.id(), id, r));
  }

  @GetMapping("/creators/me/materials")
  public ApiResponse<?> mine(
      @AuthenticationPrincipal Identity u,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    return ApiResponse.ok(catalog.mine(u.id(), page, size));
  }

  @PostMapping("/materials/{id}/publish")
  public ApiResponse<?> publish(
      @AuthenticationPrincipal Identity u,
      @PathVariable String id,
      @Valid @RequestBody Idempotency r) {
    return ApiResponse.ok(orders.publish(u.id(), id, r));
  }

  @PostMapping("/materials/{id}/off-shelf")
  public ApiResponse<?> off(@AuthenticationPrincipal Identity u, @PathVariable String id) {
    return ApiResponse.ok(catalog.offShelf(u.id(), id));
  }

  @GetMapping("/marketplace/materials")
  public ApiResponse<?> market(
      @RequestParam(required = false) String category,
      @RequestParam(required = false) String query,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    return ApiResponse.ok(catalog.market(category, query, page, size));
  }

  @GetMapping("/marketplace/materials/{id}")
  public ApiResponse<?> detail(@PathVariable String id) {
    return ApiResponse.ok(catalog.publicGet(id));
  }

  @GetMapping("/marketplace/materials/{id}/preview")
  public ApiResponse<?> preview(@PathVariable String id) {
    return ApiResponse.ok(catalog.preview(id));
  }

  @GetMapping("/marketplace/fee-rule")
  public ApiResponse<?> fee() {
    return ApiResponse.ok(catalog.currentRule());
  }

  @PostMapping("/admin/materials/{id}/reject")
  public ApiResponse<?> reject(@AuthenticationPrincipal Identity u, @PathVariable String id) {
    return ApiResponse.ok(catalog.moderate(u.id(), id));
  }

  @PostMapping("/admin/fee-rules")
  public ApiResponse<?> rule(@AuthenticationPrincipal Identity u, @Valid @RequestBody Rule r) {
    return ApiResponse.ok(catalog.rule(u.id(), r));
  }
}
