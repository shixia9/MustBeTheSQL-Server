-- V017: Schedule module enhancement — extend scheduled_task + add scheduled_run (execution history)
-- Additive only: all new columns are nullable/defaulted. Mirrors DB-GPT scheduled_task/scheduled_run blueprint.

ALTER TABLE scheduled_task
    ADD COLUMN description VARCHAR(512) NULL COMMENT 'Task description' AFTER name,
    ADD COLUMN time_zone VARCHAR(64) NULL COMMENT 'JVM ZoneId, e.g. Asia/Shanghai; null = server default' AFTER cron_expr,
    ADD COLUMN timeout_seconds INT NULL COMMENT 'Per-run timeout; null = use default 600' AFTER time_zone,
    ADD COLUMN max_retries INT NULL DEFAULT 0 COMMENT 'Retry attempts on failure (reserved)' AFTER timeout_seconds,
    ADD COLUMN last_run_status VARCHAR(16) NULL COMMENT 'success/failed/timeout' AFTER status,
    ADD COLUMN last_run_id BIGINT NULL COMMENT 'FK to scheduled_run.id of last execution' AFTER last_run_status,
    ADD COLUMN payload_version INT NULL DEFAULT 1 COMMENT 'Payload schema version' AFTER payload;

CREATE TABLE IF NOT EXISTS scheduled_run (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL COMMENT 'FK to scheduled_task.id',
    started_at DATETIME NOT NULL COMMENT 'Run start time',
    finished_at DATETIME NULL COMMENT 'Run finish time',
    status VARCHAR(16) NOT NULL COMMENT 'running / success / failed / timeout',
    result_summary TEXT NULL COMMENT 'Result summary (final text + artifact count)',
    error_message TEXT NULL COMMENT 'Error message if failed/timeout',
    output_conversation_id VARCHAR(64) NULL COMMENT 'Conversation id produced by this run',
    attempt INT NOT NULL DEFAULT 1 COMMENT 'Attempt number (for future retry support)',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_run_task_started (task_id, started_at),
    INDEX idx_run_status (status),
    CONSTRAINT fk_run_task FOREIGN KEY (task_id) REFERENCES scheduled_task(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Scheduled task execution history (one row per trigger)';
