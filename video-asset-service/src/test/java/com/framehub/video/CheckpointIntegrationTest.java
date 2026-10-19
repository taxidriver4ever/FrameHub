package com.framehub.video;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.framehub.video.cdc.CanalEvents;
import com.framehub.video.dto.UploadRequests;
import com.framehub.video.entity.*;
import com.framehub.video.mapper.*;
import com.framehub.video.media.*;
import com.framehub.video.security.*;
import com.framehub.video.service.*;
import com.framehub.video.storage.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.testcontainers.containers.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.junit.jupiter.Container;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.*;

@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class CheckpointIntegrationTest {
  @Container
  static MySQLContainer<?> mysql =
      new MySQLContainer<>("mysql:8.4").withCommand("--log-bin-trust-function-creators=1");

  @Container
  static GenericContainer<?> minio =
      new GenericContainer<>(
              "coollabsio/minio:RELEASE.2025-04-22T22-12-26Z@sha256:a4938f37f1be1841b8e7b627ad0207b265345fd0d063e42d7410c78af0e63e68")
          .withEnv("MINIO_ROOT_USER", "test-access")
          .withEnv("MINIO_ROOT_PASSWORD", "test-secret-12345")
          .withCommand("server /data")
          .withExposedPorts(9000);

  static String endpoint() {
    return "http://" + minio.getHost() + ":" + minio.getMappedPort(9000);
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", mysql::getJdbcUrl);
    r.add("spring.datasource.username", mysql::getUsername);
    r.add("spring.datasource.password", mysql::getPassword);
    r.add("video.endpoint", CheckpointIntegrationTest::endpoint);
    r.add("video.public-endpoint", CheckpointIntegrationTest::endpoint);
    r.add("video.access-key", () -> "test-access");
    r.add("video.secret-key", () -> "test-secret-12345");
    r.add("video.consumer-enabled", () -> false);
    r.add("media.worker.enabled", () -> false);
    r.add("video.cleanup-enabled", () -> false);
    r.add("video.database", mysql::getDatabaseName);
  }

  @Autowired TranscodePipeline pipeline;
  @Autowired VideoMapper videos;
  @Autowired TaskMapper tasks;
  @Autowired ObjectStorage storage;
  @Autowired ObjectMapper json;
  @Autowired UploadService uploads;
  @Autowired CanalEvents parser;
  @Autowired com.framehub.video.cdc.CdcRecovery recovery;
  @Autowired org.springframework.test.web.servlet.MockMvc mvc;
  @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
  @MockitoBean IdentityClient identityClient;
  @MockitoSpyBean UploadTransactions uploadTx;
  @MockitoSpyBean com.framehub.video.config.VideoProperties properties;
  @MockitoSpyBean CheckpointTransactions tx;
  @MockitoSpyBean StageArtifacts artifacts;
  @MockitoBean MediaEngine media;
  String video, event;
  long user;
  Path root;
  List<Integer> encoded;

  @BeforeEach
  void setup() throws Exception {
    try (var s3 =
        S3Client.builder()
            .endpointOverride(URI.create(endpoint()))
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create("test-access", "test-secret-12345")))
            .region(Region.US_EAST_1)
            .forcePathStyle(true)
            .build()) {
      try {
        s3.createBucket(b -> b.bucket("videos"));
      } catch (software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException ignored) {
      }
    }
    root = Files.createTempDirectory("checkpoint-test-");
    encoded = new ArrayList<>();
    video = UUID.randomUUID().toString();
    event = UUID.randomUUID().toString();
    user = Math.abs(UUID.randomUUID().getMostSignificantBits() >>> 1);
    when(media.workspace(anyString()))
        .thenAnswer(i -> Files.createTempDirectory(root, i.getArgument(0, String.class)));
    when(media.probe(any(), any()))
        .thenReturn(new MediaEngine.Metadata(3, 1920, 1080, 25, 1000000, "mp4"));
    when(media.heights(any())).thenReturn(List.of(480, 720, 1080));
    doAnswer(
            i -> {
              int height = i.getArgument(3);
              encoded.add(height);
              Path dir = i.getArgument(1);
              Files.writeString(dir.resolve("seg-00000.ts"), "segment-" + height);
              Files.writeString(
                  dir.resolve("index.m3u8"), "#EXTM3U\n#EXTINF:3,\nseg-00000.ts\n#EXT-X-ENDLIST\n");
              return null;
            })
        .when(media)
        .hls(any(), any(), any(), anyInt(), any());
    doAnswer(
            i -> {
              Files.writeString(i.getArgument(1, Path.class).resolve("cover.jpg"), "cover-data");
              return null;
            })
        .when(media)
        .cover(any(), any(), any());
    VideoAsset v = new VideoAsset();
    v.videoId = video;
    v.userId = user;
    v.creatorId = "creator";
    v.title = "Test";
    v.sourceKey = "sources/" + user + "/" + video + "/original";
    v.sourceSize = 10;
    videos.insertVideo(v);
    videos.status(video, "UPLOADED", null);
    Path source = root.resolve("input");
    Files.writeString(source, "0123456789");
    storage.upload(v.sourceKey, source, "video/mp4");
    videos.outbox(event, video, user, "{}");
  }

  @AfterEach
  void cleanup() throws Exception {
    try (var paths = Files.walk(root)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
    }
  }

  void run() throws Exception {
    pipeline.process(video, event, user);
  }

  TranscodeStep step(String stage) {
    return tasks.step(tasks.byVideo(video).taskId, stage);
  }

  @Test
  void resumes1080FailureAndReuses480And720() throws Exception {
    doThrow(new IllegalStateException("encode crash"))
        .doCallRealMethod()
        .when(artifacts)
        .save(argThat(s -> s.stageName.equals("TRANSCODE_1080P")), any(), any(), any());
    assertThatThrownBy(this::run).hasMessageContaining("encode crash");
    assertThat(step("TRANSCODE_720P").status).isEqualTo("SUCCEEDED");
    run();
    assertThat(encoded).containsExactly(480, 720, 1080, 1080);
    assertThat(videos.video(video).status).isEqualTo("READY");
  }

  @Test
  void interruptedStageRunsAgain() throws Exception {
    doThrow(new IllegalStateException("FFmpeg crash"))
        .doAnswer(
            i -> {
              encoded.add(480);
              Path dir = i.getArgument(1);
              Files.writeString(dir.resolve("seg-00000.ts"), "segment");
              Files.writeString(
                  dir.resolve("index.m3u8"), "#EXTM3U\nseg-00000.ts\n#EXT-X-ENDLIST\n");
              return null;
            })
        .when(media)
        .hls(any(), any(), any(), eq(480), any());
    assertThatThrownBy(this::run).hasMessageContaining("FFmpeg crash");
    assertThat(step("TRANSCODE_480P").status).isEqualTo("FAILED");
    run();
    assertThat(videos.video(video).status).isEqualTo("READY");
  }

  @Test
  void partialUploadsAreNotCheckpoints() throws Exception {
    doAnswer(
            i -> {
              TranscodeStep s = i.getArgument(0);
              storage.text(s.artifactPrefix + "index.m3u8", "partial");
              throw new IllegalStateException("upload crash");
            })
        .doCallRealMethod()
        .when(artifacts)
        .save(argThat(s -> s.stageName.equals("TRANSCODE_720P")), any(), any(), any());
    assertThatThrownBy(this::run).hasMessageContaining("upload crash");
    String old = step("TRANSCODE_720P").artifactPrefix;
    assertThat(storage.head(old + "manifest.json")).isNull();
    run();
    assertThat(step("TRANSCODE_720P").artifactPrefix).isNotEqualTo(old);
    assertThat(encoded).containsExactly(480, 720, 720, 1080);
  }

  @Test
  void completeManifestSurvivesCheckpointCommitFailure() throws Exception {
    doThrow(new IllegalStateException("database commit failure"))
        .doCallRealMethod()
        .when(tx)
        .success(any(), eq("TRANSCODE_720P"), any());
    assertThatThrownBy(this::run).hasMessageContaining("database commit failure");
    String prefix = step("TRANSCODE_720P").artifactPrefix;
    run();
    assertThat(step("TRANSCODE_720P").artifactPrefix).isEqualTo(prefix);
    assertThat(encoded).containsExactly(480, 720, 1080);
  }

  @Test
  void readyRedeliveryDoesNotEncodeAgain() throws Exception {
    run();
    String prefix = videos.video(video).outputPrefix;
    run();
    assertThat(encoded).containsExactly(480, 720, 1080);
    assertThat(videos.video(video).outputPrefix).isEqualTo(prefix);
  }

  @Test
  void sourceChangeInvalidatesCheckpoints() throws Exception {
    run();
    Path source = root.resolve("changed");
    Files.writeString(source, "9876543210");
    storage.upload(videos.video(video).sourceKey, source, "video/mp4");
    run();
    assertThat(encoded).containsExactly(480, 720, 1080, 480, 720, 1080);
  }

  @Test
  void configChangeCannotReuseManifest() throws Exception {
    run();
    String oldPrefix = step("TRANSCODE_720P").artifactPrefix;
    when(properties.configVersion()).thenReturn("hls-v2");
    run();
    assertThat(step("TRANSCODE_720P").artifactPrefix).isNotEqualTo(oldPrefix);
    assertThat(encoded).containsExactly(480, 720, 1080, 480, 720, 1080);
  }

  @Test
  void staleExecutionCannotPublishOrCommit() throws Exception {
    run();
    var old = tasks.byVideo(video);
    var active = tx.acquire(video, event, old.sourceVersion, old.configVersion);
    assertThatThrownBy(() -> tx.success(old, "COVER", "{}"))
        .isInstanceOf(CheckpointTransactions.Stale.class);
    assertThatThrownBy(() -> tx.publish(old, List.of(), "bad", "bad", "[]"))
        .isInstanceOf(CheckpointTransactions.Stale.class);
    assertThatThrownBy(() -> tx.acquire(video, event, old.sourceVersion, old.configVersion))
        .isInstanceOf(CheckpointTransactions.Busy.class);
    tx.failure(active, null, new RuntimeException("test stop"));
  }

  @Test
  void missingStagePreventsReady() throws Exception {
    var task = tx.acquire(video, event, "s", "c");
    assertThatThrownBy(() -> tx.publish(task, List.of("COVER"), "bad", "bad", "[]"))
        .hasMessageContaining("Missing current checkpoint");
    assertThat(videos.video(video).status).isEqualTo("PROCESSING");
  }

  @Test
  void missingSegmentOrInvalidManifestIsDetected() throws Exception {
    run();
    var s = step("TRANSCODE_720P");
    storage.delete(s.artifactPrefix + "seg-00000.ts");
    assertThat(artifacts.verify(s, Map.of())).isNull();
    run();
    assertThat(encoded).containsExactly(480, 720, 1080, 720);
    s = step("TRANSCODE_720P");
    storage.text(s.manifestKey, "{}");
    assertThat(artifacts.verify(step("TRANSCODE_720P"), Map.of())).isNull();
  }

  @Test
  void multipartPresignAndActualLengthValidation() throws Exception {
    var identity = new Identity("" + user, "creator", List.of("CREATOR"));
    var session = uploads.init(identity, new UploadRequests.Init("x.mp4", "Test", 10, "video/mp4"));
    var signed =
        uploads.presign(user, session.uploadSessionId(), new UploadRequests.Presign(1, 10));
    HttpClient http = HttpClient.newHttpClient();
    var response =
        http.send(
            HttpRequest.newBuilder(URI.create(signed.uploadUrl()))
                .PUT(HttpRequest.BodyPublishers.ofString("0123456789"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(uploads.progress(user, session.uploadSessionId()).uploadedParts()).hasSize(1);
    uploads.complete(user, session.uploadSessionId());
    assertThat(videos.video(session.videoId()).status).isEqualTo("UPLOADED");
  }

  @Test
  void missingPartsRemainResumableAndOwnerChecked() {
    var session =
        uploads.init(
            new Identity("" + user, "creator", List.of("CREATOR")),
            new UploadRequests.Init("x.mp4", "Test", 10, "video/mp4"));
    assertThatThrownBy(() -> uploads.complete(user, session.uploadSessionId()))
        .isInstanceOf(UploadService.MissingParts.class);
    assertThat(uploads.progress(user, session.uploadSessionId()).status()).isEqualTo("UPLOADING");
    assertThatThrownBy(() -> uploads.progress(user + 1, session.uploadSessionId()))
        .isInstanceOf(com.framehub.video.common.ApiException.class);
    uploads.abort(user, session.uploadSessionId());
    assertThat(uploads.progress(user, session.uploadSessionId()).status()).isEqualTo("ABORTED");
  }

  @Test
  void canalMultiRowEnvelopeAndVersionValidation() throws Exception {
    Map<String, Object> row =
        Map.of(
            "event_id",
            event,
            "video_id",
            video,
            "user_id",
            "" + user,
            "event_type",
            "VIDEO_TRANSCODE_REQUESTED",
            "event_version",
            "1");
    String envelope =
        json.writeValueAsString(
            Map.of(
                "database",
                mysql.getDatabaseName(),
                "table",
                "outbox_event",
                "type",
                "INSERT",
                "isDdl",
                false,
                "data",
                List.of(row, row)));
    assertThat(parser.parse(envelope)).hasSize(2);
    assertThatThrownBy(() -> parser.parse(envelope.replace("VIDEO_TRANSCODE_REQUESTED", "UNKNOWN")))
        .isInstanceOf(CanalEvents.InvalidEvent.class);
  }

  @Test
  void oversizedPartIsRejectedBySignatureOrFinalValidation() throws Exception {
    var session =
        uploads.init(
            new Identity("" + user, "creator", List.of("CREATOR")),
            new UploadRequests.Init("x.mp4", "Test", 10, "video/mp4"));
    var signed =
        uploads.presign(user, session.uploadSessionId(), new UploadRequests.Presign(1, 10));
    var response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create(signed.uploadUrl()))
                    .PUT(HttpRequest.BodyPublishers.ofString("01234567890"))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    System.out.println(
        "MINIO_CONTENT_LENGTH_ENFORCEMENT status="
            + response.statusCode()
            + " signedHeaders="
            + signed.requiredHeaders().keySet());
    if (response.statusCode() == 200) {
      assertThatThrownBy(() -> uploads.complete(user, session.uploadSessionId()))
          .isInstanceOf(com.framehub.video.common.ApiException.class);
      assertThat(uploads.progress(user, session.uploadSessionId()).status()).isEqualTo("FAILED");
    } else {
      assertThat(response.statusCode()).isIn(400, 403);
      uploads.abort(user, session.uploadSessionId());
    }
  }

  @Test
  void quotaAndConcurrentUploadReservationsAreAtomic() {
    var identity = new Identity("" + user, "creator", List.of("CREATOR"));
    assertThatThrownBy(
            () ->
                uploads.init(
                    identity, new UploadRequests.Init("x.mp4", "Test", 2147483649L, "video/mp4")))
        .isInstanceOf(com.framehub.video.common.ApiException.class);
    uploads.init(identity, new UploadRequests.Init("x.mp4", "Test", 2147483648L, "video/mp4"));
    uploads.init(identity, new UploadRequests.Init("x.mp4", "Test", 2147483648L, "video/mp4"));
    assertThatThrownBy(
            () ->
                uploads.init(
                    identity, new UploadRequests.Init("x.mp4", "Test", 2147483648L, "video/mp4")))
        .isInstanceOf(com.framehub.video.common.ApiException.class);
    uploads.init(identity, new UploadRequests.Init("x.mp4", "Test", 10, "video/mp4"));
    assertThatThrownBy(
            () -> uploads.init(identity, new UploadRequests.Init("x.mp4", "Test", 10, "video/mp4")))
        .isInstanceOf(com.framehub.video.common.ApiException.class);
  }

  @Test
  void httpAuthenticationAndCreatorAuthorization() throws Exception {
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                "/api/v1/videos/me"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isUnauthorized());
    when(identityClient.verify("Bearer member"))
        .thenReturn(new Identity("" + user, null, List.of("USER")));
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/v1/videos/me")
                .header("Authorization", "Bearer member"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isForbidden());
    when(identityClient.verify("Bearer creator"))
        .thenReturn(new Identity("" + user, "creator", List.of("CREATOR")));
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/v1/videos/" + video)
                .header("Authorization", "Bearer creator"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
  }

  @Test
  void mergedObjectRecoversAfterDatabaseFailureWithoutDuplicateOutbox() throws Exception {
    var session =
        uploads.init(
            new Identity("" + user, "creator", List.of("CREATOR")),
            new UploadRequests.Init("x.mp4", "Test", 10, "video/mp4"));
    var signed =
        uploads.presign(user, session.uploadSessionId(), new UploadRequests.Presign(1, 10));
    assertThat(
            HttpClient.newHttpClient()
                .send(
                    HttpRequest.newBuilder(URI.create(signed.uploadUrl()))
                        .PUT(HttpRequest.BodyPublishers.ofString("0123456789"))
                        .build(),
                    HttpResponse.BodyHandlers.discarding())
                .statusCode())
        .isEqualTo(200);
    doThrow(new IllegalStateException("commit failed"))
        .doCallRealMethod()
        .when(uploadTx)
        .finish(eq(session.uploadSessionId()), any());
    assertThatThrownBy(() -> uploads.complete(user, session.uploadSessionId()))
        .hasMessageContaining("commit failed");
    assertThat(uploads.progress(user, session.uploadSessionId()).status()).isEqualTo("COMPLETING");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE video_id=?",
                Integer.class,
                session.videoId()))
        .isZero();
    uploads.complete(user, session.uploadSessionId());
    uploads.complete(user, session.uploadSessionId());
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE video_id=?",
                Integer.class,
                session.videoId()))
        .isEqualTo(1);
  }

  @Test
  void expiredExecutionCanResumeButActiveExecutionCannot() throws Exception {
    var abandoned = tx.acquire(video, event, "source", "config");
    assertThatThrownBy(this::run).isInstanceOf(CheckpointTransactions.Busy.class);
    jdbc.update(
        "UPDATE video_transcode_task SET"
            + " processing_deadline=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP) WHERE task_id=?",
        abandoned.taskId);
    run();
    assertThat(videos.video(video).status).isEqualTo("READY");
    assertThat(tasks.byVideo(video).executionVersion).isGreaterThan(abandoned.executionVersion);
    assertThatThrownBy(() -> tx.success(abandoned, "COVER", "{}"))
        .isInstanceOf(CheckpointTransactions.Stale.class);
  }

  @Test
  void failureRecoveryPersistsTerminalStateAndQuarantineTogether() throws Exception {
    Map<String, Object> row =
        Map.of(
            "event_id",
            event,
            "video_id",
            video,
            "user_id",
            "" + user,
            "event_type",
            "VIDEO_TRANSCODE_REQUESTED",
            "event_version",
            "1");
    String payload =
        json.writeValueAsString(
            Map.of(
                "database",
                mysql.getDatabaseName(),
                "table",
                "outbox_event",
                "type",
                "INSERT",
                "data",
                List.of(row)));
    var record =
        new org.apache.kafka.clients.consumer.ConsumerRecord<String, String>(
            "video.outbox.cdc", 0, user, null, payload);
    var active = tx.acquire(video, event, "source", "config");
    assertThatThrownBy(() -> recovery.persist(record, new RuntimeException("exhausted")))
        .isInstanceOf(CheckpointTransactions.Busy.class);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM failed_cdc_events WHERE kafka_offset=?", Integer.class, user))
        .isZero();
    tx.failure(active, null, new RuntimeException("failed"));
    recovery.persist(record, new RuntimeException("exhausted"));
    assertThat(videos.video(video).status).isEqualTo("FAILED");
    assertThat(tasks.byVideo(video).status).isEqualTo("FAILED");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM failed_cdc_events WHERE kafka_offset=?", Integer.class, user))
        .isEqualTo(1);
  }

  @Autowired com.framehub.video.worker.TaskIngestion ingestion;

  @Test
  void kafkaIngestionCommitsDurableTaskOnceBeforeMediaRuns() {
    var events = List.of(new CanalEvents.Event(event, video, user));
    ingestion.accept(events);
    ingestion.accept(events);
    assertThat(tasks.byVideo(video).status).isEqualTo("PENDING");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM event_inbox WHERE event_id=?", Integer.class, event))
        .isEqualTo(1);
    assertThat(encoded).isEmpty();
  }

  @Test
  void leaseExpiryFencesOldWorkerAndAllowsRecovery() throws Exception {
    var old = tx.acquire(video, event, "source", "config");
    assertThat(tasks.heartbeat(old.taskId, old.executionVersion, 120)).isEqualTo(1);
    jdbc.update(
        "UPDATE video_transcode_task SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP)"
            + " WHERE task_id=?",
        old.taskId);
    assertThat(tasks.heartbeat(old.taskId, old.executionVersion, 120)).isZero();
    assertThatThrownBy(() -> tx.success(old, "PROBE", "{}"))
        .isInstanceOf(CheckpointTransactions.Stale.class);
    var replacement = tx.acquire(video, event, "source", "config");
    pipeline.processClaimed(replacement);
    assertThat(videos.video(video).status).isEqualTo("READY");
    assertThat(tasks.heartbeat(old.taskId, old.executionVersion, 120)).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=? AND"
                    + " event_type='ASSET_READY'",
                Integer.class,
                video))
        .isEqualTo(1);
  }
}
