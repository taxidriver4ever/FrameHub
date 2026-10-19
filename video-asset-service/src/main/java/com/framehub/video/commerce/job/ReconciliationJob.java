package com.framehub.video.commerce.job;

import com.framehub.video.commerce.service.ReconciliationService;
import java.time.*;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "commerce.jobs-enabled", havingValue = "true")
public class ReconciliationJob {
  private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);
  private final ReconciliationService service;

  public ReconciliationJob(ReconciliationService service) {
    this.service = service;
  }

  @Scheduled(cron = "${commerce.reconciliation-cron:0 30 10 * * *}", zone = "Asia/Shanghai")
  public void run() {
    try {
      service.channel(0, LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(1));
    } catch (Exception e) {
      log.error("T+1 reconciliation deferred: {}", e.getClass().getSimpleName());
    }
  }
}
