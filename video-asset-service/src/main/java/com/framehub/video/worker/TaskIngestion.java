package com.framehub.video.worker;

import com.framehub.video.cdc.CanalEvents;
import com.framehub.video.entity.TranscodeTask;
import com.framehub.video.mapper.*;
import com.framehub.video.messaging.EventMapper;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskIngestion {
  private final TaskMapper tasks;
  private final VideoMapper videos;
  private final EventMapper events;

  public TaskIngestion(TaskMapper tasks, VideoMapper videos, EventMapper events) {
    this.tasks = tasks;
    this.videos = videos;
    this.events = events;
  }

  @Transactional
  public void accept(List<CanalEvents.Event> input) {
    for (var event : input) {
      if (videos.eventExists(event.eventId(), event.videoId(), event.userId()) != 1)
        throw new IllegalArgumentException("Untrusted outbox event");
      if (events.inbox("media-task", event.eventId()) == 0) continue;
      var t = new TranscodeTask();
      t.taskId = UUID.randomUUID().toString();
      t.videoId = event.videoId();
      t.eventId = event.eventId();
      t.sourceVersion = "pending";
      t.configVersion = "pending";
      tasks.create(t);
    }
  }
}
