package com.framehub.video.media;

import java.time.Instant;

public final class ProcessingBudget {
  private static final ThreadLocal<Instant> DEADLINE = new ThreadLocal<>();

  private ProcessingBudget() {}

  public static void start(Instant deadline) {
    DEADLINE.set(deadline);
  }

  public static void clear() {
    DEADLINE.remove();
  }

  public static Instant cap(Instant deadline) {
    Instant record = DEADLINE.get();
    return record != null && record.isBefore(deadline) ? record : deadline;
  }

  public static void check() {
    Instant end = DEADLINE.get();
    if (Thread.currentThread().isInterrupted() || (end != null && !Instant.now().isBefore(end)))
      throw new IllegalStateException("Record processing deadline or cancellation");
  }
}
