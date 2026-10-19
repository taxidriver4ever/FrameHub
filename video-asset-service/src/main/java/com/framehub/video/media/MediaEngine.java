package com.framehub.video.media;

import com.fasterxml.jackson.databind.*;
import com.framehub.video.config.VideoProperties;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Component;

@Component
public class MediaEngine {
  public record Metadata(
      double duration, int width, int height, double fps, long bitrate, String format) {}

  public record Rendition(int height, String playlist) {}

  private final VideoProperties p;
  private final ProcessRunner runner;
  private final ObjectMapper json;
  private final Semaphore permits;

  public MediaEngine(VideoProperties p, ProcessRunner runner, ObjectMapper json) {
    this.p = p;
    this.runner = runner;
    this.json = json;
    permits = new Semaphore(p.ffmpegConcurrency(), true);
  }

  public Path workspace(String prefix) throws Exception {
    Path root = p.workDir().toAbsolutePath().normalize();
    Files.createDirectories(root);
    if (Files.getFileStore(root).getUsableSpace() < p.maxTempBytes())
      throw new IllegalStateException("Insufficient temporary disk capacity");
    return Files.createTempDirectory(root, prefix);
  }

  public Metadata probe(Path source, Path dir) throws Exception {
    acquire();
    try {
      runner.run(
          List.of(
              p.ffprobe(),
              "-v",
              "error",
              "-protocol_whitelist",
              "file",
              "-format_whitelist",
              "mov,matroska,webm,avi,mpegts",
              "-show_format",
              "-show_streams",
              "-of",
              "json",
              source.toAbsolutePath().toString()),
          dir,
          "probe",
          Instant.now().plus(p.probeTimeout()));
      Path output = dir.resolve("probe.stdout");
      if (Files.size(output) > 1024 * 1024) throw new InvalidMedia("Probe output too large");
      JsonNode root = json.readTree(Files.readString(output));
      JsonNode stream = null;
      for (JsonNode s : root.path("streams"))
        if ("video".equals(s.path("codec_type").asText())
            && s.path("disposition").path("attached_pic").asInt() == 0) {
          stream = s;
          break;
        }
      if (stream == null
          || !Set.of("h264", "hevc", "vp8", "vp9", "av1", "mpeg4", "mpeg2video")
              .contains(stream.path("codec_name").asText()))
        throw new InvalidMedia("Unsupported video codec");
      double duration = root.path("format").path("duration").asDouble(-1);
      int width = stream.path("width").asInt(), height = stream.path("height").asInt();
      if (!Double.isFinite(duration)
          || duration <= 0
          || duration > p.maxDurationSeconds()
          || width < 2
          || height < 2
          || width > p.maxDimension()
          || height > p.maxDimension()) throw new InvalidMedia("Invalid duration or dimensions");
      String format = root.path("format").path("format_name").asText();
      if (Arrays.stream(format.split(","))
          .noneMatch(Set.of("mov", "mp4", "matroska", "webm", "avi", "mpegts")::contains))
        throw new InvalidMedia("Unsupported container");
      String[] rate = stream.path("avg_frame_rate").asText("0/1").split("/");
      double fps =
          Double.parseDouble(rate[0]) / Double.parseDouble(rate.length > 1 ? rate[1] : "1");
      if (!Double.isFinite(fps) || fps <= 0 || fps > 240)
        throw new InvalidMedia("Invalid frame rate");
      return new Metadata(
          duration, width, height, fps, root.path("format").path("bit_rate").asLong(0), format);
    } catch (ProcessRunner.ProcessFailure e) {
      throw new InvalidMedia("Invalid or unreadable media");
    } finally {
      permits.release();
    }
  }

  public List<Integer> heights(Metadata m) {
    var heights =
        p.renditions().stream()
            .filter(h -> h > 0 && h <= m.height() && h % 2 == 0)
            .distinct()
            .sorted()
            .toList();
    return heights.isEmpty() ? List.of(m.height() / 2 * 2) : heights;
  }

  public void hls(Path source, Path output, Metadata m, int height, Instant deadline)
      throws Exception {
    acquire();
    try {
      Files.createDirectories(output);
      int width = Math.max(2, (int) ((double) m.width() * height / m.height()) / 2 * 2);
      String filter = "scale=" + width + ":" + height;
      if (!p.watermark().isBlank())
        filter +=
            ",drawtext=text='"
                + p.watermark()
                + "':fontcolor=white@0.7:fontsize=24:x=w-tw-12:y=h-th-12";
      runner.run(
          List.of(
              p.ffmpeg(),
              "-hide_banner",
              "-nostdin",
              "-y",
              "-loglevel",
              "warning",
              "-protocol_whitelist",
              "file",
              "-format_whitelist",
              "mov,matroska,webm,avi,mpegts",
              "-i",
              source.toAbsolutePath().toString(),
              "-map",
              "0:v:0",
              "-map",
              "0:a:0?",
              "-vf",
              filter,
              "-c:v",
              "libx264",
              "-preset",
              "veryfast",
              "-crf",
              "24",
              "-threads",
              "" + p.ffmpegThreads(),
              "-filter_threads",
              "1",
              "-c:a",
              "aac",
              "-b:a",
              "128k",
              "-force_key_frames",
              "expr:gte(t,n_forced*6)",
              "-f",
              "hls",
              "-hls_time",
              "6",
              "-hls_playlist_type",
              "vod",
              "-hls_segment_filename",
              output.resolve("seg-%05d.ts").toAbsolutePath().toString(),
              output.resolve("index.m3u8").toAbsolutePath().toString()),
          output,
          "encode",
          deadline);
      validateHls(output);
    } finally {
      permits.release();
    }
  }

  public void cover(Path source, Path output, Instant deadline) throws Exception {
    acquire();
    try {
      Files.createDirectories(output);
      runner.run(
          List.of(
              p.ffmpeg(),
              "-hide_banner",
              "-nostdin",
              "-y",
              "-loglevel",
              "error",
              "-protocol_whitelist",
              "file",
              "-ss",
              "0",
              "-i",
              source.toAbsolutePath().toString(),
              "-frames:v",
              "1",
              "-threads",
              "1",
              "-vf",
              "scale='min(640,iw)':-2",
              output.resolve("cover.jpg").toAbsolutePath().toString()),
          output,
          "cover",
          deadline);
      if (Files.size(output.resolve("cover.jpg")) == 0) throw new InvalidMedia("Empty cover");
    } finally {
      permits.release();
    }
  }

  public void validateHls(Path output) throws Exception {
    String content = Files.readString(output.resolve("index.m3u8"));
    if (!content.startsWith("#EXTM3U") || !content.contains("#EXT-X-ENDLIST"))
      throw new InvalidMedia("Incomplete HLS playlist");
    int count = 0;
    for (String line : content.lines().toList()) {
      if (line.isBlank() || line.startsWith("#")) continue;
      if (!line.matches("seg-[0-9]{5}\\.ts")) throw new InvalidMedia("Unsafe HLS reference");
      Path file = output.resolve(line);
      if (!Files.isRegularFile(file) || Files.size(file) == 0)
        throw new InvalidMedia("Missing segment");
      if (++count > p.maxOutputFiles()) throw new InvalidMedia("Too many output files");
    }
    if (count == 0) throw new InvalidMedia("No segments");
  }

  private void acquire() {
    if (!permits.tryAcquire()) throw new IllegalStateException("Media concurrency limit reached");
  }

  public void cleanup(Path dir) {
    Path root = p.workDir().toAbsolutePath().normalize(), target = dir.toAbsolutePath().normalize();
    if (!target.startsWith(root) || target.equals(root))
      throw new IllegalArgumentException("Unsafe cleanup path");
    try (var files = Files.walk(target)) {
      for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
    } catch (java.io.IOException e) {
      org.slf4j.LoggerFactory.getLogger(MediaEngine.class).warn("Temporary cleanup deferred");
    }
  }

  public static class InvalidMedia extends RuntimeException {
    public InvalidMedia(String message) {
      super(message);
    }
  }
}
