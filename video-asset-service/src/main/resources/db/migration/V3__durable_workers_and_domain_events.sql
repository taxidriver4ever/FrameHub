ALTER TABLE outbox_event MODIFY video_id CHAR(36) NULL, ADD aggregate_type VARCHAR(32) NULL, ADD aggregate_id VARCHAR(64) NULL, ADD trace_id VARCHAR(64) NULL;
UPDATE outbox_event SET aggregate_type='VIDEO',aggregate_id=video_id,trace_id=event_id;
ALTER TABLE outbox_event MODIFY aggregate_type VARCHAR(32) NOT NULL, MODIFY aggregate_id VARCHAR(64) NOT NULL, ADD UNIQUE KEY uk_aggregate_event(aggregate_type,aggregate_id,event_type,event_version),ADD INDEX ix_outbox_archive(created_at);
CREATE TABLE event_inbox(consumer_name VARCHAR(64) NOT NULL,event_id CHAR(36) NOT NULL,processed_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),PRIMARY KEY(consumer_name,event_id));
ALTER TABLE video_transcode_task ADD lease_until TIMESTAMP(6) NULL,ADD lease_owner VARCHAR(64) NULL,ADD heartbeat_at TIMESTAMP(6) NULL,ADD next_retry_at TIMESTAMP(6) NULL,ADD INDEX ix_worker_due(status,next_retry_at,lease_until);
ALTER TABLE video_transcode_task DROP CHECK video_transcode_task_chk_1;
ALTER TABLE video_transcode_task ADD CONSTRAINT ck_task_status CHECK(status IN('PENDING','PROCESSING','READY','FAILED','RETRY_WAIT','CANCELLED'));
