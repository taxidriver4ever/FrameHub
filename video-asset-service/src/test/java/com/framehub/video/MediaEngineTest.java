package com.framehub.video;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.framehub.video.config.VideoProperties;
import com.framehub.video.media.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class MediaEngineTest {
  @TempDir Path root;

  VideoProperties properties() {
    var p = mock(VideoProperties.class);
    when(p.ffmpeg()).thenReturn(System.getenv().getOrDefault("FFMPEG_PATH", "ffmpeg"));
    when(p.ffprobe()).thenReturn(System.getenv().getOrDefault("FFPROBE_PATH", "ffprobe"));
    when(p.workDir()).thenReturn(root);
    when(p.maxTempBytes()).thenReturn(100000000L);
    when(p.ffmpegConcurrency()).thenReturn(1);
    when(p.ffmpegThreads()).thenReturn(1);
    when(p.maxDimension()).thenReturn(8192);
    when(p.maxDurationSeconds()).thenReturn(1800L);
    when(p.maxOutputFiles()).thenReturn(4096);
    when(p.probeTimeout()).thenReturn(Duration.ofSeconds(15));
    when(p.watermark()).thenReturn("FrameHub Preview");
    when(p.renditions()).thenReturn(List.of(480, 720, 1080));
    return p;
  }

  @Test
  void actualProbeHlsCoverAndNoUpscaling() throws Exception {
    var p = properties();
    var runner = new ProcessRunner(p);
    var media = new MediaEngine(p, runner, new ObjectMapper());
    Path source = root.resolve("source.mp4");
    runner.run(
        List.of(
            p.ffmpeg(),
            "-hide_banner",
            "-loglevel",
            "error",
            "-f",
            "lavfi",
            "-i",
            "testsrc2=size=640x360:rate=25",
            "-t",
            "1",
            "-c:v",
            "libx264",
            "-threads",
            "1",
            source.toString()),
        root,
        "fixture",
        Instant.now().plusSeconds(30));
    var metadata = media.probe(source, root);
    assertThat(metadata.width()).isEqualTo(640);
    assertThat(media.heights(metadata)).containsExactly(360);
    media.hls(source, root.resolve("hls"), metadata, 360, Instant.now().plusSeconds(30));
    media.validateHls(root.resolve("hls"));
    media.cover(source, root.resolve("cover"), Instant.now().plusSeconds(30));
    assertThat(Files.size(root.resolve("cover/cover.jpg"))).isPositive();
  }

  @Test
  void timeoutTerminatesProcessAndReturnsPromptly() throws Exception {
    var p = properties();
    long start = System.nanoTime();
    assertThatThrownBy(
            () ->
                new ProcessRunner(p)
                    .run(
                        List.of(
                            p.ffmpeg(),
                            "-hide_banner",
                            "-loglevel",
                            "error",
                            "-re",
                            "-f",
                            "lavfi",
                            "-i",
                            "testsrc2=size=64x64:rate=1",
                            "-f",
                            "null",
                            "-"),
                        root,
                        "timeout",
                        Instant.now().plusMillis(400)))
        .isInstanceOf(ProcessRunner.ProcessFailure.class);
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(6));
    // Windows may release redirected file handles just after the process exit signal.
    // Verify bounded cleanup instead of leaving locked logs for @TempDir teardown.
    org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
      Files.deleteIfExists(root.resolve("timeout.stdout"));
      Files.deleteIfExists(root.resolve("timeout.stderr"));
    });
  }
}
