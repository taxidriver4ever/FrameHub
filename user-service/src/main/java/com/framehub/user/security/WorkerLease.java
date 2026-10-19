package com.framehub.user.security;

import com.framehub.user.common.Secrets;
import com.framehub.user.config.AppProperties;
import jakarta.annotation.PostConstruct;
import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Worker leases prevent simultaneous reuse; an expired lease fails closed until restart. */
@Component
public class WorkerLease {
  private final RedisState state;
  private final String key, owner = Secrets.token();
  private volatile long deadline;

  public WorkerLease(RedisState state, AppProperties p) {
    this.state = state;
    key = "worker:" + p.workerId();
  }

  @PostConstruct
  void acquire() {
    if (!state.once(key, owner, 60)) throw new IllegalStateException("Worker ID already leased");
    deadline = System.nanoTime() + 50_000_000_000L;
  }

  @Scheduled(fixedDelay = 15000)
  void renew() {
    if (System.nanoTime() > deadline) return;
    long started = System.nanoTime();
    try {
      if (state.script(
              "if redis.call('GET',KEYS[1])==ARGV[1] then return redis.call('EXPIRE',KEYS[1],60)"
                  + " end; return 0",
              List.of(key),
              owner)
          == 1) deadline = started + 50_000_000_000L;
      else deadline = 0;
    } catch (RuntimeException e) {
      deadline = 0;
    }
  }

  public void check() {
    if (System.nanoTime() > deadline) throw new IllegalStateException("Worker lease lost");
  }
}
