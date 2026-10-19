package com.framehub.video.worker;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("media.worker")
public record WorkerProperties(boolean enabled, Duration lease, Duration heartbeat) {
  public WorkerProperties {
    if (lease == null) lease = Duration.ofMinutes(2);
    if (heartbeat == null) heartbeat = Duration.ofSeconds(20);
    if (lease.compareTo(heartbeat.multipliedBy(3)) < 0)
      throw new IllegalArgumentException("Lease must allow three heartbeat intervals");
  }
}
