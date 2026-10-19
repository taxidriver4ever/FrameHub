package com.framehub.video.commerce.job;

import com.framehub.video.commerce.mapper.WithdrawalMapper;
import com.framehub.video.commerce.service.WithdrawalService;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "commerce.jobs-enabled", havingValue = "true")
public class WithdrawalRecoveryJob {
  private static final Logger log = LoggerFactory.getLogger(WithdrawalRecoveryJob.class);
  private final WithdrawalMapper db;
  private final WithdrawalService service;

  public WithdrawalRecoveryJob(WithdrawalMapper db, WithdrawalService service) {
    this.db = db;
    this.service = service;
  }

  @Scheduled(fixedDelayString = "${commerce.withdrawal-delay-ms:15000}")
  public void run() {
    for (String id : db.pending())
      try {
        service.sync(id);
      } catch (Exception e) {
        log.warn("Withdrawal recovery deferred id={} error={}", id, e.getClass().getSimpleName());
      }
  }
}
