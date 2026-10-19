package com.framehub.video.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.framehub.video.common.ApiException;
import com.framehub.video.config.VideoProperties;
import com.framehub.video.entity.VideoAsset;
import com.framehub.video.mapper.VideoMapper;
import com.framehub.video.media.MediaEngine;
import com.framehub.video.storage.ObjectStorage;
import com.framehub.video.vo.VideoViews;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class VideoService {
  private final VideoMapper mapper;
  private final ObjectStorage storage;
  private final ObjectMapper json;
  private final VideoProperties p;

  public VideoService(
      VideoMapper mapper, ObjectStorage storage, ObjectMapper json, VideoProperties p) {
    this.mapper = mapper;
    this.storage = storage;
    this.json = json;
    this.p = p;
  }

  public VideoAsset owned(long user, String id) {
    var v = mapper.video(id);
    if (v == null) throw new ApiException(404, "视频不存在");
    if (v.userId != user) throw new ApiException(403, "没有此视频的预览权限");
    return v;
  }

  public VideoViews.Video get(long user, String id) {
    return view(owned(user, id));
  }

  public VideoViews.Page list(long user, int page, int size, String status) {
    if (page < 1 || size < 1 || size > 100) throw new ApiException(400, "分页参数不合法");
    if (status != null
        && !Set.of("UPLOADING", "UPLOADED", "PROCESSING", "READY", "FAILED", "ABORTED")
            .contains(status)) throw new ApiException(400, "状态不合法");
    return new VideoViews.Page(
        mapper.list(user, status, size, (long) (page - 1) * size).stream().map(this::view).toList(),
        mapper.count(user, status),
        page,
        size);
  }

  public List<MediaEngine.Rendition> renditions(VideoAsset v) {
    if (v.outputsJson == null) return List.of();
    try {
      return json.readValue(v.outputsJson, new TypeReference<List<MediaEngine.Rendition>>() {});
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private VideoViews.Video view(VideoAsset v) {
    return new VideoViews.Video(
        v.videoId,
        "" + v.userId,
        v.creatorId,
        v.title,
        v.status,
        v.sourceSize,
        v.durationSeconds,
        v.width,
        v.height,
        v.fps,
        v.bitrate,
        v.coverKey == null ? null : storage.signedGet(v.coverKey),
        renditions(v).stream().map(MediaEngine.Rendition::height).toList(),
        v.createdAt,
        v.updatedAt);
  }

  public VideoViews.Playback playback(long user, String id) {
    var v = owned(user, id);
    if (!"READY".equals(v.status)) throw new ApiException(409, "视频尚未处理完成");
    String temporary = "playback/" + v.videoId + "/" + UUID.randomUUID() + "/";
    String master = storage.read(v.outputPrefix + "master.m3u8");
    for (var rendition : renditions(v)) {
      String original = storage.read(rendition.playlist());
      String prefix = rendition.playlist().substring(0, rendition.playlist().lastIndexOf('/') + 1);
      StringBuilder rewritten = new StringBuilder();
      for (String line : original.lines().toList()) {
        if (!line.isBlank() && !line.startsWith("#")) {
          if (!line.matches("seg-[0-9]{5}\\.ts"))
            throw new IllegalStateException("Invalid stored playlist");
          line = storage.signedGet(prefix + line);
        }
        rewritten.append(line).append('\n');
      }
      String key = temporary + "h" + rendition.height() + ".m3u8";
      storage.text(key, rewritten.toString());
      master = master.replace(rendition.playlist(), storage.signedGet(key));
    }
    String key = temporary + "master.m3u8";
    storage.text(key, master);
    return new VideoViews.Playback(
        storage.signedGet(key),
        renditions(v).stream().map(MediaEngine.Rendition::height).toList(),
        p.presignTtl().toSeconds());
  }
}
