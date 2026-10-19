package com.framehub.video.service;

import com.framehub.video.config.VideoProperties;
import com.framehub.video.entity.*;
import com.framehub.video.mapper.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CheckpointTransactions {
  private final com.framehub.video.worker.WorkerProperties worker;
  private final com.framehub.video.messaging.DomainOutbox outbox;
  private final String owner = UUID.randomUUID().toString();
  private final TaskMapper tasks;
  private final VideoMapper videos;
  private final VideoProperties p;

  public CheckpointTransactions(
      TaskMapper tasks,
      VideoMapper videos,
      VideoProperties p,
      com.framehub.video.worker.WorkerProperties worker,
      com.framehub.video.messaging.DomainOutbox outbox) {
    this.worker = worker;
    this.outbox = outbox;
    this.tasks = tasks;
    this.videos = videos;
    this.p = p;
  }

  @Transactional
  public TranscodeTask acquire(String video, String event, String source, String config) {
    TranscodeTask seed = new TranscodeTask();
    seed.taskId = UUID.randomUUID().toString();
    seed.videoId = video;
    seed.eventId = event;
    seed.sourceVersion = source;
    seed.configVersion = config;
    tasks.create(seed);
    TranscodeTask t = tasks.lock(tasks.byVideo(video).taskId);
    if (!t.eventId.equals(event)) throw new IllegalArgumentException("Event identity mismatch");
    if ("PROCESSING".equals(t.status)
        && t.processingDeadline != null
        && t.processingDeadline.isAfter(Instant.now())
        && (t.leaseUntil == null || t.leaseUntil.isAfter(Instant.now())))
      throw new Busy("Active execution has not expired");
    if ("FAILED".equals(t.status)) throw new Terminal("Task exhausted its retry budget");
    if (t.attempt >= p.maxAttempts() && !"READY".equals(t.status))
      throw new Terminal("Task exhausted its retry budget");
    t.attemptId = UUID.randomUUID().toString();
    t.leaseOwner = owner;
    t.leaseUntil = Instant.now().plus(worker.lease());
    t.processingDeadline =
        com.framehub.video.media.ProcessingBudget.cap(Instant.now().plus(p.jobTimeout()));
    t.sourceVersion = source;
    t.configVersion = config;
    if (tasks.begin(t) != 1) throw new Stale();
    t.executionVersion++;
    t.attempt++;
    t.status = "PROCESSING";
    videos.status(video, "PROCESSING", null);
    return t;
  }

  private TranscodeTask guard(TranscodeTask t) {
    TranscodeTask current = tasks.lock(t.taskId);
    if (current.executionVersion != t.executionVersion
        || !"PROCESSING".equals(current.status)
        || !current.processingDeadline.isAfter(Instant.now())
        || current.leaseUntil != null && !current.leaseUntil.isAfter(Instant.now()))
      throw new Stale();
    return current;
  }

  @Transactional
  public void start(TranscodeTask t, TranscodeStep s) {
    guard(t);
    tasks.start(s);
  }

  @Transactional
  public void success(TranscodeTask t, String stage, String result) {
    guard(t);
    tasks.success(t.taskId, stage, t.executionVersion, result);
  }

  @Transactional
  public void failure(TranscodeTask t, String stage, Exception cause) {
    TranscodeTask current = tasks.lock(t.taskId);
    if (current.executionVersion != t.executionVersion || !"PROCESSING".equals(current.status))
      return;
    String error =
        cause.getClass().getSimpleName() + ": " + Objects.toString(cause.getMessage(), "");
    error = error.substring(0, Math.min(1000, error.length()));
    if (stage != null) tasks.stepFailed(t.taskId, stage, error);
    boolean terminal = t.attempt >= p.maxAttempts();
    tasks.failed(t.taskId, t.executionVersion, terminal ? "FAILED" : "RETRY_WAIT", error);
    tasks.retryAt(t.taskId, p.retryBackoff().toSeconds());
    videos.status(t.videoId, terminal ? "FAILED" : "UPLOADED", error);
  }

  @Transactional
  public void publish(
      TranscodeTask t, List<String> required, String prefix, String cover, String outputs) {
    guard(t);
    for (String stage : required) {
      TranscodeStep s = tasks.step(t.taskId, stage);
      if (s == null
          || !"SUCCEEDED".equals(s.status)
          || !t.sourceVersion.equals(s.sourceVersion)
          || !t.configVersion.equals(s.configVersion)
          || s.executionVersion != t.executionVersion)
        throw new IllegalStateException("Missing current checkpoint: " + stage);
    }
    tasks.success(t.taskId, "PUBLISH", t.executionVersion, "{}");
    if (tasks.ready(t) != 1) throw new Stale();
    tasks.publish(t.videoId, prefix, cover, outputs);
    outbox.emit(
        "VIDEO",
        t.videoId,
        "ASSET_READY",
        Math.toIntExact(t.executionVersion),
        videos.video(t.videoId).userId,
        Map.of("videoId", t.videoId, "executionVersion", t.executionVersion));
  }

  @Transactional
  public void initializeVersions(TranscodeTask t, String source, String config) {
    guard(t);
    t.sourceVersion = source;
    t.configVersion = config;
    if (tasks.versions(t) != 1) throw new Stale();
  }

  @Transactional
  public void exhausted(String id) {
    var t = tasks.lock(id);
    if (t != null
        && !"READY".equals(t.status)
        && (!"PROCESSING".equals(t.status)
            || t.leaseUntil == null
            || t.leaseUntil.isBefore(Instant.now())
            || t.processingDeadline.isBefore(Instant.now()))) {
      tasks.terminate(id, "Retry budget exhausted");
      videos.status(t.videoId, "FAILED", "Retry budget exhausted");
    }
  }

  public static class Busy extends RuntimeException {
    public Busy(String message) {
      super(message);
    }
  }

  public static class Stale extends Busy {
    public Stale() {
      super("Execution ownership changed");
    }
  }

  public static class Terminal extends RuntimeException {
    public Terminal(String message) {
      super(message);
    }
  }
}
