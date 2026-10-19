package com.framehub.video.commerce.job;

import com.framehub.video.commerce.mapper.TradeMapper;
import com.framehub.video.commerce.service.PaymentService;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "commerce.jobs-enabled", havingValue = "true")
public class TradeRecoveryJob {
  private static final Logger log = LoggerFactory.getLogger(TradeRecoveryJob.class);
  private final TradeMapper db;
  private final PaymentService payments;

  public TradeRecoveryJob(TradeMapper db, PaymentService payments) {
    this.db = db;
    this.payments = payments;
  }

  @Scheduled(fixedDelayString = "${commerce.recovery-delay-ms:15000}")
  public void recover() {
    for (String id : db.pendingPayments()) run("payment", id, () -> payments.sync(id));
    for (String id : db.expired()) run("close", id, () -> payments.close(0, id, true));
    for (String id : db.pendingRefunds()) run("refund", id, () -> payments.syncRefund(id));
  }

  private void run(String type, String id, Runnable action) {
    try {
      action.run();
    } catch (Exception e) {
      log.warn(
          "Trade recovery deferred: type={} id={} error={}",
          type,
          id,
          e.getClass().getSimpleName());
    }
  }
}
