package com.framehub.video.mapper;

import com.framehub.video.entity.*;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface TaskMapper {
  @Insert(
      "INSERT INTO video_transcode_task(task_id,video_id,event_id,source_version,config_version)"
          + " VALUES(#{taskId},#{videoId},#{eventId},#{sourceVersion},#{configVersion}) ON"
          + " DUPLICATE KEY UPDATE task_id=task_id")
  void create(TranscodeTask task);

  @Select("SELECT * FROM video_transcode_task WHERE video_id=#{id}")
  TranscodeTask byVideo(String id);

  @Select("SELECT * FROM video_transcode_task WHERE task_id=#{id} FOR UPDATE")
  TranscodeTask lock(String id);

  @Update(
      "UPDATE video_transcode_task SET"
          + " status='PROCESSING',execution_version=execution_version+1,attempt=attempt+1,attempt_id=#{attemptId},processing_deadline=#{processingDeadline},source_version=#{sourceVersion},config_version=#{configVersion},last_error=NULL,lease_until=#{leaseUntil},lease_owner=#{leaseOwner},heartbeat_at=CURRENT_TIMESTAMP(6)"
          + " WHERE task_id=#{taskId} AND execution_version=#{executionVersion}")
  int begin(TranscodeTask task);

  @Select("SELECT * FROM video_transcode_step WHERE task_id=#{id} AND stage_name=#{stage}")
  TranscodeStep step(@Param("id") String id, @Param("stage") String stage);

  @Select("SELECT * FROM video_transcode_step WHERE task_id=#{id}")
  List<TranscodeStep> steps(String id);

  @Insert(
      "INSERT INTO"
          + " video_transcode_step(id,task_id,stage_name,status,idempotency_key,artifact_prefix,manifest_key,attempt_id,execution_version,config_version,source_version)"
          + " VALUES(#{id},#{taskId},#{stageName},'RUNNING',#{idempotencyKey},#{artifactPrefix},#{manifestKey},#{attemptId},#{executionVersion},#{configVersion},#{sourceVersion})"
          + " ON DUPLICATE KEY UPDATE"
          + " status='RUNNING',idempotency_key=VALUES(idempotency_key),artifact_prefix=VALUES(artifact_prefix),manifest_key=VALUES(manifest_key),attempt_id=VALUES(attempt_id),execution_version=VALUES(execution_version),config_version=VALUES(config_version),source_version=VALUES(source_version),result_json=NULL,completed_at=NULL,last_error=NULL,retry_count=retry_count+1")
  void start(TranscodeStep step);

  @Update(
      "UPDATE video_transcode_step SET"
          + " status='SUCCEEDED',execution_version=#{version},result_json=#{result},completed_at=CURRENT_TIMESTAMP(6),last_error=NULL"
          + " WHERE task_id=#{task} AND stage_name=#{stage}")
  void success(
      @Param("task") String task,
      @Param("stage") String stage,
      @Param("version") long version,
      @Param("result") String result);

  @Update(
      "UPDATE video_transcode_step SET status='FAILED',last_error=#{error} WHERE task_id=#{task}"
          + " AND stage_name=#{stage}")
  void stepFailed(
      @Param("task") String task, @Param("stage") String stage, @Param("error") String error);

  @Update(
      "UPDATE video_transcode_task SET"
          + " status=#{status},retry_count=retry_count+1,last_error=#{error} WHERE task_id=#{task}"
          + " AND execution_version=#{version} AND status='PROCESSING'")
  int failed(
      @Param("task") String task,
      @Param("version") long version,
      @Param("status") String status,
      @Param("error") String error);

  @Update(
      "UPDATE video_transcode_task SET status='READY',last_error=NULL WHERE task_id=#{taskId} AND"
          + " execution_version=#{executionVersion} AND status='PROCESSING'")
  int ready(TranscodeTask task);

  @Update(
      "UPDATE video_assets SET"
          + " status='READY',output_prefix=#{prefix},cover_key=#{cover},outputs_json=#{outputs},failure_reason=NULL"
          + " WHERE video_id=#{video}")
  void publish(
      @Param("video") String video,
      @Param("prefix") String prefix,
      @Param("cover") String cover,
      @Param("outputs") String outputs);

  @Update(
      "UPDATE video_transcode_task SET status='FAILED',last_error=#{error} WHERE task_id=#{task}"
          + " AND status!='READY'")
  void terminate(@Param("task") String task, @Param("error") String error);

  @Select(
      "SELECT COUNT(*) FROM video_transcode_step s JOIN video_transcode_task t ON"
          + " s.task_id=t.task_id WHERE s.artifact_prefix=#{prefix} AND t.status IN"
          + " ('PENDING','RETRY_WAIT','PROCESSING','READY')")
  int referenced(String prefix);

  @Select(
      "SELECT * FROM video_transcode_task WHERE status IN ('PENDING','RETRY_WAIT') AND"
          + " (next_retry_at IS NULL OR next_retry_at<=CURRENT_TIMESTAMP(6)) OR status='PROCESSING'"
          + " AND (lease_until<CURRENT_TIMESTAMP(6) OR processing_deadline<CURRENT_TIMESTAMP(6))"
          + " ORDER BY created_at LIMIT #{limit}")
  List<TranscodeTask> due(int limit);

  @Update(
      "UPDATE video_transcode_task SET"
          + " lease_until=TIMESTAMPADD(SECOND,#{seconds},CURRENT_TIMESTAMP(6)),heartbeat_at=CURRENT_TIMESTAMP(6)"
          + " WHERE task_id=#{task} AND execution_version=#{version} AND status='PROCESSING' AND"
          + " lease_until>CURRENT_TIMESTAMP(6) AND processing_deadline>CURRENT_TIMESTAMP(6)")
  int heartbeat(
      @Param("task") String task, @Param("version") long version, @Param("seconds") long seconds);

  @Update(
      "UPDATE video_transcode_task SET"
          + " source_version=#{sourceVersion},config_version=#{configVersion} WHERE"
          + " task_id=#{taskId} AND execution_version=#{executionVersion} AND status='PROCESSING'")
  int versions(TranscodeTask task);

  @Update(
      "UPDATE video_transcode_task SET"
          + " next_retry_at=TIMESTAMPADD(SECOND,#{seconds},CURRENT_TIMESTAMP(6)) WHERE"
          + " task_id=#{task}")
  void retryAt(@Param("task") String task, @Param("seconds") long seconds);
}
