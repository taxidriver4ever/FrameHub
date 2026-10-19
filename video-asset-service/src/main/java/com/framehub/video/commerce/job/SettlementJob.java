package com.framehub.video.commerce.job;

import com.framehub.video.commerce.consumer.CommerceEvents;
import com.framehub.video.commerce.mapper.*;
import com.framehub.video.commerce.service.*;
import java.time.Instant;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "commerce.jobs-enabled", havingValue = "true")
public class SettlementJob {
  private static final Logger log = LoggerFactory.getLogger(SettlementJob.class);
  private final LedgerMapper ledger;
  private final SettlementService settlements;
  private final CommerceEventMapper failures;
  private final CommerceEvents events;

  public SettlementJob(
      LedgerMapper ledger,
      SettlementService settlements,
      CommerceEventMapper failures,
      CommerceEvents events) {
    this.ledger = ledger;
    this.settlements = settlements;
    this.failures = failures;
    this.events = events;
  }

  @Scheduled(fixedDelayString = "${commerce.settlement-delay-ms:10000}")
  public void run() {
    for (String id : ledger.due())
      try {
        settlements.release(id);
      } catch (Exception e) {
        log.warn("Earning release deferred order={} error={}", id, e.getClass().getSimpleName());
      }
    for (var f : failures.due())
      try {
        events.accept(f.payload);
        failures.resolved(f.id);
      } catch (Exception e) {
        failures.retry(
            f.id,
            Instant.now().plusSeconds(Math.min(3600, 30L * (f.attempts + 1))),
            e.getClass().getSimpleName());
      }
  }
}
