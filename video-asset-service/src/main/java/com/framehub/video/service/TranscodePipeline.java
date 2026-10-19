package com.framehub.video.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.framehub.video.config.VideoProperties;
import com.framehub.video.entity.*;
import com.framehub.video.mapper.*;
import com.framehub.video.media.MediaEngine;
import com.framehub.video.storage.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class TranscodePipeline {
  private final VideoMapper videos;
  private final TaskMapper tasks;
  private final CheckpointTransactions tx;
  private final ObjectStorage store;
  private final StageArtifacts artifacts;
  private final MediaEngine media;
  private final ObjectMapper json;
  private final VideoProperties p;

  public TranscodePipeline(
      VideoMapper videos,
      TaskMapper tasks,
      CheckpointTransactions tx,
      ObjectStorage store,
      StageArtifacts artifacts,
      MediaEngine media,
      ObjectMapper json,
      VideoProperties p) {
    this.videos = videos;
    this.tasks = tasks;
    this.tx = tx;
    this.store = store;
    this.artifacts = artifacts;
    this.media = media;
    this.json = json;
    this.p = p;
  }

  @FunctionalInterface
  interface Work {
    String run(Path dir) throws Exception;
  }

  private final class Run {
    final TranscodeTask task;
    final VideoAsset video;
    final Path root;
    Path source;
    String stage;
    long mediaMillis;

    Run(TranscodeTask task, VideoAsset video, Path root) {
      this.task = task;
      this.video = video;
      this.root = root;
    }

    void budget() {
      if (Thread.currentThread().isInterrupted()
          || !Instant.now().isBefore(task.processingDeadline))
        throw new IllegalStateException("Pipeline deadline or cancellation");
    }

    Path source() throws Exception {
      budget();
      if (source == null) {
        source = root.resolve("source");
        store.download(video.sourceKey, source, p.maxFileBytes(), task.processingDeadline);
      }
      return source;
    }

    Instant mediaDeadline() {
      budget();
      long remaining = p.ffmpegTimeout().toMillis() - mediaMillis;
      if (remaining <= 0) throw new IllegalStateException("Aggregate FFmpeg budget exhausted");
      Instant end = Instant.now().plusMillis(remaining);
      return end.isBefore(task.processingDeadline) ? end : task.processingDeadline;
    }

    TranscodeStep stage(String name, Map<String, String> dependencies, Work work) throws Exception {
      budget();
      stage = name;
      String key =
          StageArtifacts.hash(
              video.videoId + "|" + task.sourceVersion + "|" + name + "|" + task.configVersion);
      TranscodeStep old = tasks.step(task.taskId, name);
      if (old != null && key.equals(old.idempotencyKey)) {
        StageArtifacts.Manifest manifest = artifacts.verify(old, dependencies);
        if (manifest != null) {
          budget();
          tx.success(task, name, manifest.result());
          return tasks.step(task.taskId, name);
        }
      }
      TranscodeStep step = new TranscodeStep();
      step.id = UUID.randomUUID().toString();
      step.taskId = task.taskId;
      step.stageName = name;
      step.idempotencyKey = key;
      step.sourceVersion = task.sourceVersion;
      step.configVersion = task.configVersion;
      step.executionVersion = task.executionVersion;
      step.attemptId = task.attemptId;
      step.artifactPrefix =
          "processed/"
              + video.videoId
              + "/"
              + task.sourceVersion
              + "/"
              + task.attemptId
              + "/"
              + name
              + "-"
              + UUID.randomUUID()
              + "/";
      step.manifestKey = step.artifactPrefix + "manifest.json";
      tx.start(task, step);
      Path dir = Files.createDirectory(root.resolve(name + "-" + UUID.randomUUID()));
      String result = work.run(dir);
      budget();
      artifacts.save(step, dir, dependencies, result);
      budget();
      if (artifacts.verify(step, dependencies) == null)
        throw new IllegalStateException("Artifact validation failed");
      tx.success(task, name, result);
      return tasks.step(task.taskId, name);
    }
  }

  public void process(String videoId, String eventId, long userId) throws Exception {
    if (com.framehub.video.media.ProcessingBudget.cap(Instant.now().plus(p.jobTimeout()))
        .isBefore(Instant.now().plusSeconds(300)))
      throw new CheckpointTransactions.Busy(
          "Continue remaining rows on redelivery within record budget");
    if (videos.eventExists(eventId, videoId, userId) != 1)
      throw new IllegalArgumentException("Outbox identity does not match database");
    VideoAsset video = videos.video(videoId);
    var head = store.head(video.sourceKey);
    if (head == null || head.contentLength() != video.sourceSize)
      throw new IllegalStateException("Source missing or changed size");
    String sourceVersion =
        StageArtifacts.hash(video.sourceKey + "|" + head.eTag() + "|" + head.contentLength());
    String configVersion =
        StageArtifacts.hash(
            p.configVersion()
                + "|"
                + p.renditions()
                + "|"
                + p.watermark()
                + "|x264-crf24-aac128-hls6");
    TranscodeTask existing = tasks.byVideo(videoId);
    if (existing != null
        && "READY".equals(existing.status)
        && sourceVersion.equals(existing.sourceVersion)
        && configVersion.equals(existing.configVersion)
        && publishedValid(existing)) return;
    TranscodeTask task = tx.acquire(videoId, eventId, sourceVersion, configVersion);
    execute(task, video);
  }

  public void processClaimed(TranscodeTask task) throws Exception {
    VideoAsset video = videos.video(task.videoId);
    var head = store.head(video.sourceKey);
    if (head == null || head.contentLength() != video.sourceSize)
      throw new IllegalStateException("Source missing or changed size");
    tx.initializeVersions(
        task,
        StageArtifacts.hash(video.sourceKey + "|" + head.eTag() + "|" + head.contentLength()),
        StageArtifacts.hash(
            p.configVersion()
                + "|"
                + p.renditions()
                + "|"
                + p.watermark()
                + "|x264-crf24-aac128-hls6"));
    execute(task, video);
  }

  private void execute(TranscodeTask task, VideoAsset video) throws Exception {
    String videoId = video.videoId,
        sourceVersion = task.sourceVersion,
        configVersion = task.configVersion;
    Run run = null;
    Path root = null;
    try {
      root = media.workspace("transcode-");
      run = new Run(task, video, root);
      final Run r = run;
      TranscodeStep probe =
          r.stage("PROBE", Map.of(), dir -> json.writeValueAsString(media.probe(r.source(), dir)));
      MediaEngine.Metadata metadata = json.readValue(probe.resultJson, MediaEngine.Metadata.class);
      List<String> required = new ArrayList<>(List.of("PROBE"));
      List<MediaEngine.Rendition> outputs = new ArrayList<>();
      Map<String, String> dependencies = new TreeMap<>();
      for (int height : media.heights(metadata)) {
        String name = "TRANSCODE_" + height + "P";
        TranscodeStep s =
            r.stage(
                name,
                Map.of(),
                dir -> {
                  Path input = r.source();
                  long start = System.nanoTime();
                  try {
                    media.hls(input, dir, metadata, height, r.mediaDeadline());
                  } finally {
                    r.mediaMillis += Duration.ofNanos(System.nanoTime() - start).toMillis();
                  }
                  return "{}";
                });
        required.add(name);
        outputs.add(new MediaEngine.Rendition(height, s.artifactPrefix + "index.m3u8"));
        dependencies.put(s.artifactPrefix + "index.m3u8", s.manifestKey);
      }
      TranscodeStep cover =
          r.stage(
              "COVER",
              Map.of(),
              dir -> {
                Path input = r.source();
                long start = System.nanoTime();
                try {
                  media.cover(input, dir, r.mediaDeadline());
                } finally {
                  r.mediaMillis += Duration.ofNanos(System.nanoTime() - start).toMillis();
                }
                return "{}";
              });
      required.add("COVER");
      TranscodeStep master =
          r.stage(
              "MASTER_PLAYLIST",
              dependencies,
              dir -> {
                StringBuilder text = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n");
                for (var out : outputs) {
                  int width =
                      Math.max(
                          2,
                          (int) ((double) metadata.width() * out.height() / metadata.height())
                              / 2
                              * 2);
                  text.append("#EXT-X-STREAM-INF:BANDWIDTH=")
                      .append(Math.max(400000, out.height() * 4000))
                      .append(",RESOLUTION=")
                      .append(width)
                      .append('x')
                      .append(out.height())
                      .append('\n')
                      .append(out.playlist())
                      .append('\n');
                }
                Files.writeString(dir.resolve("master.m3u8"), text);
                return json.writeValueAsString(outputs);
              });
      required.add("MASTER_PLAYLIST");
      // VERIFY always checks real objects even when its previous checkpoint is reusable.
      for (String name : required) {
        TranscodeStep s = tasks.step(task.taskId, name);
        if (artifacts.verify(s, name.equals("MASTER_PLAYLIST") ? dependencies : Map.of()) == null)
          throw new IllegalStateException("Output disappeared before publication");
      }
      Map<String, String> verified = new TreeMap<>(dependencies);
      verified.put("cover", cover.manifestKey);
      verified.put("master", master.manifestKey);
      r.stage("VERIFY", verified, dir -> "{}");
      required.add("VERIFY");
      TranscodeStep publish = new TranscodeStep();
      publish.id = UUID.randomUUID().toString();
      publish.taskId = task.taskId;
      publish.stageName = "PUBLISH";
      publish.idempotencyKey =
          StageArtifacts.hash(videoId + sourceVersion + configVersion + "PUBLISH");
      publish.sourceVersion = sourceVersion;
      publish.configVersion = configVersion;
      publish.executionVersion = task.executionVersion;
      publish.attemptId = task.attemptId;
      tx.start(task, publish);
      r.stage = "PUBLISH";
      r.budget();
      tx.publish(
          task,
          required,
          master.artifactPrefix,
          cover.artifactPrefix + "cover.jpg",
          json.writeValueAsString(outputs));
    } catch (Exception e) {
      tx.failure(task, run == null ? null : run.stage, e);
      throw e;
    } finally {
      if (root != null) media.cleanup(root);
    }
  }

  private boolean publishedValid(TranscodeTask task) throws Exception {
    VideoAsset published = videos.video(task.videoId);
    TranscodeStep master = tasks.step(task.taskId, "MASTER_PLAYLIST"),
        cover = tasks.step(task.taskId, "COVER");
    if (!"READY".equals(published.status)
        || master == null
        || cover == null
        || !Objects.equals(published.outputPrefix, master.artifactPrefix)
        || !Objects.equals(published.coverKey, cover.artifactPrefix + "cover.jpg")
        || !json.readTree(published.outputsJson).equals(json.readTree(master.resultJson)))
      return false;
    List<TranscodeStep> steps = tasks.steps(task.taskId);
    Map<String, String> dependencies = new TreeMap<>();
    for (var s : steps)
      if (s.stageName.startsWith("TRANSCODE_")
          && s.sourceVersion.equals(task.sourceVersion)
          && s.configVersion.equals(task.configVersion))
        dependencies.put(s.artifactPrefix + "index.m3u8", s.manifestKey);
    if (dependencies.isEmpty()) return false;
    Set<String> found = new HashSet<>();
    for (var s : steps) {
      if (s.stageName.equals("PUBLISH") || s.stageName.equals("VERIFY")) continue;
      if (!s.sourceVersion.equals(task.sourceVersion)
          || !s.configVersion.equals(task.configVersion)) continue;
      if (!"SUCCEEDED".equals(s.status)
          || artifacts.verify(s, s.stageName.equals("MASTER_PLAYLIST") ? dependencies : Map.of())
              == null) return false;
      found.add(s.stageName);
    }
    return found.containsAll(List.of("PROBE", "COVER", "MASTER_PLAYLIST"));
  }
}
