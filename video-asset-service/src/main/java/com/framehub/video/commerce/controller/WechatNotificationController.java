package com.framehub.video.commerce.controller;

import com.framehub.video.commerce.service.PaymentService;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class WechatNotificationController {
  private final PaymentService payments;

  public WechatNotificationController(PaymentService payments) {
    this.payments = payments;
  }

  @PostMapping("/payment-notifications/wechat")
  public ResponseEntity<Void> notify(
      @RequestHeader Map<String, String> headers, @RequestBody String body) {
    payments.notification(headers, body);
    return ResponseEntity.noContent().build();
  }
}
