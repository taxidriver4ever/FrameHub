package com.framehub.video.mapper;

import com.framehub.video.entity.*;
import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface VideoMapper {
  @Insert("INSERT INTO user_storage(user_id) VALUES(#{id}) ON DUPLICATE KEY UPDATE user_id=user_id")
  void ensureQuota(long id);

  @Select("SELECT * FROM user_storage WHERE user_id=#{id} FOR UPDATE")
  StorageQuota quota(long id);

  @Update(
      "UPDATE user_storage SET"
          + " reserved_bytes=reserved_bytes+#{bytes},active_uploads=active_uploads+1 WHERE"
          + " user_id=#{id}")
  void reserve(@Param("id") long id, @Param("bytes") long bytes);

  @Update(
      "UPDATE user_storage SET"
          + " reserved_bytes=reserved_bytes-#{bytes},active_uploads=active_uploads-1,used_bytes=used_bytes+#{used}"
          + " WHERE user_id=#{id}")
  void release(@Param("id") long id, @Param("bytes") long bytes, @Param("used") long used);

  @Insert(
      "INSERT INTO video_assets(video_id,user_id,creator_id,title,source_key,source_size)"
          + " VALUES(#{videoId},#{userId},#{creatorId},#{title},#{sourceKey},#{sourceSize})")
  void insertVideo(VideoAsset video);

  @Insert(
      "INSERT INTO"
          + " upload_sessions(session_id,video_id,user_id,object_key,upload_id,declared_size,part_bytes,part_count,content_type,status,expires_at)"
          + " VALUES(#{sessionId},#{videoId},#{userId},#{objectKey},#{uploadId},#{declaredSize},#{partBytes},#{partCount},#{contentType},#{status},#{expiresAt})")
  void insertUpload(UploadSession upload);

  @Select("SELECT * FROM upload_sessions WHERE session_id=#{id}")
  UploadSession session(String id);

  @Select("SELECT * FROM upload_sessions WHERE session_id=#{id} FOR UPDATE")
  UploadSession lockSession(String id);

  @Update("UPDATE upload_sessions SET status=#{status} WHERE session_id=#{id}")
  void sessionStatus(@Param("id") String id, @Param("status") String status);

  @Select("SELECT * FROM video_assets WHERE video_id=#{id}")
  VideoAsset video(String id);

  @Select("SELECT * FROM video_assets WHERE video_id=#{id} FOR UPDATE")
  VideoAsset lockVideo(String id);

  @Update("UPDATE video_assets SET status=#{status},failure_reason=#{reason} WHERE video_id=#{id}")
  void status(
      @Param("id") String id, @Param("status") String status, @Param("reason") String reason);

  @Update(
      "UPDATE video_assets SET"
          + " status='UPLOADED',duration_seconds=#{durationSeconds},width=#{width},height=#{height},fps=#{fps},bitrate=#{bitrate},format_name=#{formatName}"
          + " WHERE video_id=#{videoId}")
  void uploaded(VideoAsset video);

  @Insert(
      "INSERT INTO"
          + " outbox_event(event_id,video_id,user_id,event_type,event_version,payload,aggregate_type,aggregate_id,trace_id)"
          + " VALUES(#{eventId},#{videoId},#{userId},'VIDEO_TRANSCODE_REQUESTED',1,#{payload},'VIDEO',#{videoId},#{eventId})")
  void outbox(
      @Param("eventId") String eventId,
      @Param("videoId") String videoId,
      @Param("userId") long userId,
      @Param("payload") String payload);

  @Select(
      "SELECT COUNT(*) FROM outbox_event WHERE event_id=#{eventId} AND video_id=#{videoId} AND"
          + " user_id=#{userId} AND event_type='VIDEO_TRANSCODE_REQUESTED' AND event_version=1")
  int eventExists(
      @Param("eventId") String eventId,
      @Param("videoId") String videoId,
      @Param("userId") long userId);

  @Select(
      "<script>SELECT * FROM video_assets WHERE user_id=#{id}<if test='status!=null'> AND"
          + " status=#{status}</if> ORDER BY created_at DESC,video_id DESC LIMIT #{limit} OFFSET"
          + " #{offset}</script>")
  List<VideoAsset> list(
      @Param("id") long id,
      @Param("status") String status,
      @Param("limit") int limit,
      @Param("offset") long offset);

  @Select(
      "<script>SELECT COUNT(*) FROM video_assets WHERE user_id=#{id}<if test='status!=null'> AND"
          + " status=#{status}</if></script>")
  long count(@Param("id") long id, @Param("status") String status);

  @Select(
      "SELECT * FROM upload_sessions WHERE status IN ('ABORTING','FAILED') OR (status='UPLOADING'"
          + " AND expires_at < CURRENT_TIMESTAMP) OR (status='COMPLETING' AND updated_at <"
          + " #{before}) ORDER BY updated_at LIMIT 20")
  List<UploadSession> recoverable(java.time.Instant before);

  @Select(
      "SELECT COUNT(*) FROM upload_sessions WHERE object_key=#{key} AND status NOT IN"
          + " ('ABORTED','FAILED')")
  int sourceReferenced(String key);

  @Select(
      "SELECT COUNT(*) FROM video_assets WHERE output_prefix=#{prefix} AND (status='READY' OR"
          + " status='PROCESSING')")
  int outputReferenced(String prefix);

  @Select("SELECT COUNT(*) FROM video_assets WHERE attempt_id=#{attempt} AND status='PROCESSING'")
  int attemptActive(String attempt);

  @Update(
      "UPDATE video_assets SET"
          + " status='PROCESSING',execution_version=execution_version+1,attempts=attempts+1,attempt_id=#{attempt},event_id=#{event},failure_reason=NULL"
          + " WHERE video_id=#{id}")
  void begin(
      @Param("id") String id, @Param("attempt") String attempt, @Param("event") String event);

  @Update(
      "UPDATE video_assets SET"
          + " status='READY',output_prefix=#{prefix},cover_key=#{cover},outputs_json=#{outputs},failure_reason=NULL"
          + " WHERE video_id=#{id} AND execution_version=#{version} AND attempt_id=#{attempt} AND"
          + " status='PROCESSING'")
  int publish(
      @Param("id") String id,
      @Param("version") long version,
      @Param("attempt") String attempt,
      @Param("prefix") String prefix,
      @Param("cover") String cover,
      @Param("outputs") String outputs);

  @Update(
      "UPDATE video_assets SET status='FAILED',failure_reason=#{reason} WHERE video_id=#{id} AND"
          + " execution_version=#{version} AND status='PROCESSING'")
  int fail(@Param("id") String id, @Param("version") long version, @Param("reason") String reason);

  @Insert(
      "INSERT INTO"
          + " failed_cdc_events(record_id,topic,partition_id,kafka_offset,event_data,failure_reason)"
          + " VALUES(#{id},#{topic},#{partition},#{offset},#{data},#{reason}) ON DUPLICATE KEY"
          + " UPDATE record_id=record_id")
  void quarantine(
      @Param("id") String id,
      @Param("topic") String topic,
      @Param("partition") int partition,
      @Param("offset") long offset,
      @Param("data") String data,
      @Param("reason") String reason);
}
