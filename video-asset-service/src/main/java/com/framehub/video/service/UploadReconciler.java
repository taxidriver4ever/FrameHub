package com.framehub.video.service;

import com.framehub.video.config.VideoProperties;
import com.framehub.video.mapper.*;
import com.framehub.video.storage.ObjectStorage;
import java.time.Instant;
import java.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class UploadReconciler {
  private final com.framehub.video.media.MediaEngine media;
  private final VideoMapper videos;
  private final TaskMapper tasks;
  private final UploadService uploads;
  private final ObjectStorage store;
  private final VideoProperties p;

  public UploadReconciler(
      VideoMapper videos,
      TaskMapper tasks,
      UploadService uploads,
      ObjectStorage store,
      VideoProperties p,
      com.framehub.video.media.MediaEngine media) {
    this.media = media;
    this.videos = videos;
    this.tasks = tasks;
    this.uploads = uploads;
    this.store = store;
    this.p = p;
  }

  // Only upload/storage reconciliation. Never scans outbox or schedules transcodes.
  @Scheduled(fixedDelayString = "${VIDEO_CLEANUP_INTERVAL_MS:300000}")
  public void reconcile() {
    if (!p.cleanupEnabled()) return;
    try {
      for (var session :
          videos.recoverable(
              Instant.now().minus(p.ioTimeout()).minus(p.probeTimeout()).minusSeconds(60))) {
        try {
          if ("COMPLETING".equals(session.status))
            uploads.complete(session.userId, session.sessionId);
          else if (!"FAILED".equals(session.status))
            uploads.abort(session.userId, session.sessionId);
          else {
            store.abort(session.objectKey, session.uploadId);
            store.delete(session.objectKey);
          }
        } catch (Exception e) {
          org.slf4j.LoggerFactory.getLogger(getClass())
              .warn("Upload reconciliation deferred: {}", session.sessionId);
        }
      }
      Instant cutoff = Instant.now().minus(p.orphanGrace());
      for (var upload : store.multiparts())
        if (upload.initiated().isBefore(cutoff) && videos.sourceReferenced(upload.key()) == 0)
          store.abort(upload.key(), upload.uploadId());
      for (var object : store.objects("sources/"))
        if (object.lastModified().isBefore(cutoff) && videos.sourceReferenced(object.key()) == 0)
          store.delete(object.key());
      Map<String, Instant> prefixes = new HashMap<>();
      for (var object : store.objects("processed/")) {
        String prefix = object.key().substring(0, object.key().lastIndexOf('/') + 1);
        prefixes.merge(prefix, object.lastModified(), (a, b) -> a.isAfter(b) ? a : b);
      }
      for (var entry : prefixes.entrySet())
        if (entry.getValue().isBefore(cutoff)
            && tasks.referenced(entry.getKey()) == 0
            && videos.outputReferenced(entry.getKey()) == 0) store.deletePrefix(entry.getKey());
      java.nio.file.Path root = p.workDir().toAbsolutePath().normalize();
      if (java.nio.file.Files.isDirectory(root))
        try (var dirs = java.nio.file.Files.list(root)) {
          for (var dir : dirs.filter(java.nio.file.Files::isDirectory).toList())
            if (java.nio.file.Files.getLastModifiedTime(dir).toInstant().isBefore(cutoff))
              media.cleanup(dir);
        }
      for (var object : store.objects("playback/"))
        if (object.lastModified().isBefore(Instant.now().minus(p.presignTtl()).minusSeconds(60)))
          store.delete(object.key());
    } catch (Exception e) {
      org.slf4j.LoggerFactory.getLogger(getClass())
          .warn("Storage reconciliation deferred: {}", e.getClass().getSimpleName());
    }
  }
}
