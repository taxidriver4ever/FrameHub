package com.framehub.user.common;

import com.framehub.user.config.AppProperties;
import org.springframework.stereotype.Component;

@Component
public class Snowflake {
  private static final long EPOCH = 1704067200000L;
  private final com.framehub.user.security.WorkerLease lease;
  private final long worker;
  private long last = -1, sequence = 0;

  public Snowflake(AppProperties p, com.framehub.user.security.WorkerLease lease) {
    worker = p.workerId();
    this.lease = lease;
  }

  public synchronized long next() {
    lease.check();
    long now = System.currentTimeMillis();
    if (now < last)
      throw new IllegalStateException("Clock moved backwards; refusing ID generation");
    if (now == last) {
      sequence = (sequence + 1) & 4095;
      if (sequence == 0) {
        do {
          now = System.currentTimeMillis();
          if (now < last) throw new IllegalStateException("Clock moved backwards");
        } while (now == last);
      }
    } else sequence = 0;
    last = now;
    return ((now - EPOCH) << 22) | (worker << 12) | sequence;
  }
}
