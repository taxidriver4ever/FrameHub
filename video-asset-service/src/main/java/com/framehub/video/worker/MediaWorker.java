package com.framehub.video.worker;

import com.framehub.video.cdc.UserExecutionGate;
import com.framehub.video.config.VideoProperties;
import com.framehub.video.entity.TranscodeTask;
import com.framehub.video.mapper.*;
import com.framehub.video.service.*;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableConfigurationProperties(WorkerProperties.class)
public class MediaWorker {
  private final WorkerProperties p;
  private final VideoProperties video;
  private final TaskMapper tasks;
  private final VideoMapper videos;
  private final CheckpointTransactions tx;
  private final TranscodePipeline pipeline;
  private final UserExecutionGate gate;
  private final ExecutorService executor;
  private final ScheduledExecutorService renewer;
  private final Set<String> submitted = ConcurrentHashMap.newKeySet();
  private final Map<TranscodeTask, Thread> active = new ConcurrentHashMap<>();

  public MediaWorker(
      WorkerProperties p,
      VideoProperties video,
      TaskMapper tasks,
      VideoMapper videos,
      CheckpointTransactions tx,
      TranscodePipeline pipeline,
      UserExecutionGate gate) {
    this.p = p;
    this.video = video;
    this.tasks = tasks;
    this.videos = videos;
    this.tx = tx;
    this.pipeline = pipeline;
    this.gate = gate;
    executor =
        Executors.newFixedThreadPool(
            video.ffmpegConcurrency(), Thread.ofPlatform().name("media-worker-", 0).factory());
    renewer =
        Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("media-lease").factory());
    renewer.scheduleWithFixedDelay(
        this::renew, p.heartbeat().toMillis(), p.heartbeat().toMillis(), TimeUnit.MILLISECONDS);
  }

  @Scheduled(fixedDelayString = "${media.worker.scan-ms:2000}")
  public void dispatch() {
    if (!p.enabled()) return;
    int slots = video.ffmpegConcurrency() - submitted.size();
    if (slots <= 0) return;
    for (var queued : tasks.due(slots)) {
      if (!submitted.add(queued.taskId)) continue;
      executor.submit(() -> run(queued));
    }
  }

  private void run(TranscodeTask queued) {
    TranscodeTask claimed = null;
    try (var permit = gate.enter(videos.video(queued.videoId).userId)) {
      claimed =
          tx.acquire(queued.videoId, queued.eventId, queued.sourceVersion, queued.configVersion);
      active.put(claimed, Thread.currentThread());
      com.framehub.video.media.ProcessingBudget.start(claimed.processingDeadline);
      pipeline.processClaimed(claimed);
    } catch (CheckpointTransactions.Busy ignored) {
    } catch (CheckpointTransactions.Terminal e) {
      tx.exhausted(queued.taskId);
    } catch (Exception e) {
      if (claimed != null) tx.failure(claimed, null, e);
      org.slf4j.LoggerFactory.getLogger(getClass())
          .warn(
              "Media attempt failed task={} type={}", queued.taskId, e.getClass().getSimpleName());
    } finally {
      if (claimed != null) active.remove(claimed);
      submitted.remove(queued.taskId);
      com.framehub.video.media.ProcessingBudget.clear();
      Thread.interrupted();
    }
  }

  private void renew() {
    for (var entry : active.entrySet())
      try {
        if (tasks.heartbeat(
                entry.getKey().taskId, entry.getKey().executionVersion, p.lease().toSeconds())
            != 1) entry.getValue().interrupt();
      } catch (Exception e) {
        entry.getValue().interrupt();
      }
  }

  @PreDestroy
  public void stop() {
    renewer.shutdownNow();
    active.values().forEach(Thread::interrupt);
    executor.shutdownNow();
  }
}
