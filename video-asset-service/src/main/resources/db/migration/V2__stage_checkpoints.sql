ALTER TABLE video_assets DROP CHECK video_assets_chk_1;
ALTER TABLE video_assets ADD CONSTRAINT ck_video_status CHECK(status IN ('UPLOADING','UPLOADED','PROCESSING','READY','FAILED','ABORTED'));
CREATE TABLE video_transcode_task (
 task_id CHAR(36) PRIMARY KEY,video_id CHAR(36) NOT NULL UNIQUE,event_id CHAR(36) NOT NULL UNIQUE,status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
 execution_version BIGINT NOT NULL DEFAULT 0,attempt INT NOT NULL DEFAULT 0,attempt_id CHAR(36) NULL,processing_deadline TIMESTAMP(6) NULL,retry_count INT NOT NULL DEFAULT 0,last_error VARCHAR(1000) NULL,
 source_version CHAR(64) NOT NULL,config_version CHAR(64) NOT NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
 FOREIGN KEY(video_id) REFERENCES video_assets(video_id),CHECK(status IN ('PENDING','PROCESSING','READY','FAILED'))
);
CREATE TABLE video_transcode_step (
 id CHAR(36) PRIMARY KEY,task_id CHAR(36) NOT NULL,stage_name VARCHAR(64) NOT NULL,status VARCHAR(16) NOT NULL,
 idempotency_key CHAR(64) NOT NULL,artifact_prefix VARCHAR(512) NULL,manifest_key VARCHAR(600) NULL,attempt_id CHAR(36) NULL,execution_version BIGINT NOT NULL,
 config_version CHAR(64) NOT NULL,source_version CHAR(64) NOT NULL,result_json JSON NULL,retry_count INT NOT NULL DEFAULT 0,last_error VARCHAR(1000) NULL,completed_at TIMESTAMP(6) NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
 UNIQUE KEY uk_task_stage(task_id,stage_name),FOREIGN KEY(task_id) REFERENCES video_transcode_task(task_id),CHECK(status IN ('PENDING','RUNNING','SUCCEEDED','FAILED'))
);
