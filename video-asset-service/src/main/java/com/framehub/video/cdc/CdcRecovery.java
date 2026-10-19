package com.framehub.video.cdc;

import com.framehub.video.mapper.*;
import com.framehub.video.service.CheckpointTransactions.Busy;
import com.framehub.video.storage.StageArtifacts;
import java.time.Instant;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CdcRecovery {
  private final CanalEvents parser;
  private final VideoMapper videos;
  private final TaskMapper tasks;

  public CdcRecovery(CanalEvents parser, VideoMapper videos, TaskMapper tasks) {
    this.parser = parser;
    this.videos = videos;
    this.tasks = tasks;
  }

  @Transactional
  public void persist(ConsumerRecord<?, ?> record, Exception error) {
    String reason = error.toString();
    reason = reason.substring(0, Math.min(1000, reason.length()));
    List<CanalEvents.Event> events;
    try {
      events = parser.parse(String.valueOf(record.value()));
    } catch (CanalEvents.InvalidEvent e) {
      events = List.of();
    }
    for (var event : events) {
      if (videos.eventExists(event.eventId(), event.videoId(), event.userId()) != 1) continue;
      var task = tasks.byVideo(event.videoId());
      if (task != null) {
        task = tasks.lock(task.taskId);
        if ("READY".equals(task.status)) continue;
        if ("PROCESSING".equals(task.status) && task.processingDeadline.isAfter(Instant.now()))
          throw new Busy("Cannot quarantine a live execution");
        tasks.terminate(task.taskId, reason);
      }
      videos.status(event.videoId(), "FAILED", reason);
    }
    videos.quarantine(
        StageArtifacts.hash(record.topic() + ":" + record.partition() + ":" + record.offset()),
        record.topic(),
        record.partition(),
        record.offset(),
        String.valueOf(record.value()),
        reason);
  }
}
