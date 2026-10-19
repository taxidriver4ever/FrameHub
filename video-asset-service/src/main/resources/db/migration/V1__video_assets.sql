CREATE TABLE user_storage (
 user_id BIGINT PRIMARY KEY, reserved_bytes BIGINT NOT NULL DEFAULT 0, used_bytes BIGINT NOT NULL DEFAULT 0, active_uploads INT NOT NULL DEFAULT 0,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
 CHECK(reserved_bytes>=0 AND used_bytes>=0 AND active_uploads>=0)
);
CREATE TABLE video_assets (
 video_id CHAR(36) PRIMARY KEY,user_id BIGINT NOT NULL,creator_id VARCHAR(32) NOT NULL,title VARCHAR(128) NOT NULL,source_key VARCHAR(255) NOT NULL UNIQUE,
 source_size BIGINT NOT NULL,status VARCHAR(24) NOT NULL DEFAULT 'UPLOADING',duration_seconds DOUBLE NULL,width INT NULL,height INT NULL,fps DOUBLE NULL,bitrate BIGINT NULL,format_name VARCHAR(128) NULL,
 execution_version BIGINT NOT NULL DEFAULT 0,attempts INT NOT NULL DEFAULT 0,attempt_id CHAR(36) NULL,event_id CHAR(36) NULL,
 output_prefix VARCHAR(255) NULL,cover_key VARCHAR(255) NULL,outputs_json JSON NULL,failure_reason VARCHAR(1000) NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
 INDEX ix_video_owner_created(user_id,created_at,video_id),INDEX ix_video_owner_status(user_id,status,created_at),
 CHECK(status IN ('UPLOADING','UPLOADED','TRANSCODING','READY','FAILED','ABORTED'))
);
CREATE TABLE upload_sessions (
 session_id CHAR(36) PRIMARY KEY,video_id CHAR(36) NOT NULL UNIQUE,user_id BIGINT NOT NULL,object_key VARCHAR(255) NOT NULL UNIQUE,upload_id VARCHAR(512) NULL,
 declared_size BIGINT NOT NULL,part_bytes BIGINT NOT NULL,part_count INT NOT NULL,content_type VARCHAR(64) NOT NULL,
 status VARCHAR(24) NOT NULL,expires_at TIMESTAMP(6) NOT NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
 FOREIGN KEY(video_id) REFERENCES video_assets(video_id),INDEX ix_upload_cleanup(status,updated_at),INDEX ix_upload_expiry(status,expires_at),
 CHECK(status IN ('UPLOADING','COMPLETING','COMPLETED','ABORTING','ABORTED','FAILED'))
);
CREATE TABLE outbox_event (
 event_id CHAR(36) PRIMARY KEY,video_id CHAR(36) NOT NULL,user_id BIGINT NOT NULL,event_type VARCHAR(64) NOT NULL,event_version INT NOT NULL,payload JSON NOT NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),UNIQUE KEY uk_video_event(video_id,event_type,event_version),FOREIGN KEY(video_id) REFERENCES video_assets(video_id)
);
CREATE TABLE failed_cdc_events (
 record_id CHAR(64) PRIMARY KEY,topic VARCHAR(255) NOT NULL,partition_id INT NOT NULL,kafka_offset BIGINT NOT NULL,
 event_data MEDIUMTEXT NOT NULL,failure_reason VARCHAR(1000) NOT NULL,created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uk_failed_offset(topic,partition_id,kafka_offset)
);
