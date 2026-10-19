package com.framehub.video.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.framehub.video.config.VideoProperties;
import com.framehub.video.entity.TranscodeStep;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class StageArtifacts {
  public record Item(String objectKey, String name, long size, String etag, String sha256) {}

  public record Manifest(
      String videoId,
      String stageName,
      String attemptId,
      int count,
      String key,
      String source,
      String config,
      Map<String, String> dependencies,
      List<Item> files,
      String result) {}

  private final ObjectStorage store;
  private final ObjectMapper json;
  private final VideoProperties p;

  public StageArtifacts(ObjectStorage store, ObjectMapper json, VideoProperties p) {
    this.store = store;
    this.json = json;
    this.p = p;
  }

  public static String hash(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static String hash(Path file) throws Exception {
    var digest = MessageDigest.getInstance("SHA-256");
    try (var in = Files.newInputStream(file)) {
      byte[] b = new byte[65536];
      int n;
      while ((n = in.read(b)) != -1) digest.update(b, 0, n);
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  public void save(TranscodeStep s, Path directory, Map<String, String> dependencies, String result)
      throws Exception {
    List<Item> items = new ArrayList<>();
    try (var paths = Files.list(directory)) {
      for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
        String name = file.getFileName().toString();
        if (name.endsWith(".stdout") || name.endsWith(".stderr")) continue;
        if (!name.matches("[A-Za-z0-9_.-]+") || Files.size(file) == 0)
          throw new IllegalStateException("Invalid stage output");
        if (items.size() >= p.maxOutputFiles())
          throw new IllegalStateException("Too many artifacts");
        String sha = hash(file);
        store.uploadChecked(s.artifactPrefix + name, file, sha);
        var head = store.head(s.artifactPrefix + name);
        items.add(new Item(s.artifactPrefix + name, name, Files.size(file), head.eTag(), sha));
      }
    }
    // Manifest is the commit marker and is deliberately written after every artifact.
    store.text(
        s.manifestKey,
        json.writeValueAsString(
            new Manifest(
                s.artifactPrefix.split("/")[1],
                s.stageName,
                s.attemptId,
                items.size(),
                s.idempotencyKey,
                s.sourceVersion,
                s.configVersion,
                dependencies,
                items,
                result)));
  }

  public Manifest verify(TranscodeStep s, Map<String, String> dependencies) throws Exception {
    if (s == null || s.manifestKey == null || store.head(s.manifestKey) == null) return null;
    Manifest m;
    try {
      m = json.readValue(store.read(s.manifestKey), Manifest.class);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      return null;
    }
    if (m == null || m.files() == null) return null;
    if (!s.stageName.equals(m.stageName())
        || !s.attemptId.equals(m.attemptId())
        || !s.artifactPrefix.split("/")[1].equals(m.videoId())
        || m.count() != m.files().size()
        || !s.idempotencyKey.equals(m.key())
        || !s.sourceVersion.equals(m.source())
        || !s.configVersion.equals(m.config())
        || !dependencies.equals(m.dependencies())
        || m.files() == null
        || m.files().size() > p.maxOutputFiles()) return null;
    Set<String> names = new HashSet<>();
    for (Item f : m.files()) {
      if (!Objects.equals(f.objectKey(), s.artifactPrefix + f.name())
          || !f.name().matches("[A-Za-z0-9_.-]+")
          || !names.add(f.name())
          || f.size() <= 0) return null;
      var head = store.head(s.artifactPrefix + f.name());
      if (head == null
          || head.contentLength() != f.size()
          || !f.etag().equals(head.eTag())
          || !f.sha256().equals(head.metadata().get("sha256"))) return null;
    }
    if (s.stageName.startsWith("TRANSCODE_")) {
      if (!names.contains("index.m3u8")) return null;
      String playlist = store.read(s.artifactPrefix + "index.m3u8");
      if (!playlist.startsWith("#EXTM3U") || !playlist.contains("#EXT-X-ENDLIST")) return null;
      int count = 0;
      for (String line : playlist.lines().toList())
        if (!line.isBlank() && !line.startsWith("#")) {
          if (!line.matches("seg-[0-9]{5}\\.ts") || !names.contains(line)) return null;
          count++;
        }
      if (count == 0) return null;
    }
    if ("COVER".equals(s.stageName) && !names.contains("cover.jpg")) return null;
    if ("MASTER_PLAYLIST".equals(s.stageName)) {
      if (!names.contains("master.m3u8")) return null;
      String text = store.read(s.artifactPrefix + "master.m3u8");
      if (!text.startsWith("#EXTM3U")) return null;
      List<String> refs = text.lines().filter(x -> !x.isBlank() && !x.startsWith("#")).toList();
      if (refs.isEmpty() || !new HashSet<>(refs).equals(dependencies.keySet())) return null;
    }
    return m;
  }
}
