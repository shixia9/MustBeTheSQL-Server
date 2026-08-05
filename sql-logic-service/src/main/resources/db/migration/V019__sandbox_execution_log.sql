-- V019: Sandbox execution audit log — one row per sandbox code execution.
-- Aligns with the production-grade audit requirement (Task 8): every agent-driven
-- or manual Python/Shell execution is persisted for traceability and debugging.
-- stdout/stderr are intentionally TEXT (truncated to 10000 chars by the service
-- before insert) to bound row size.

CREATE TABLE IF NOT EXISTS sandbox_execution_log (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    thread_id    VARCHAR(64)  NOT NULL COMMENT 'Conversation thread id (or manual-exec token)',
    user_id      BIGINT       NULL     COMMENT 'Operator user id (best-effort from sa-token)',
    session_id   VARCHAR(64)  NULL     COMMENT 'Underlying sandbox session id',
    language     VARCHAR(16)  NOT NULL COMMENT 'python / bash / javascript / ...',
    runtime      VARCHAR(16)  NULL     COMMENT 'docker / local / legacy',
    code         TEXT         NOT NULL COMMENT 'Executed source code (before stdin shim)',
    stdout       TEXT         NULL     COMMENT 'Truncated stdout (<=10000 chars)',
    stderr       TEXT         NULL     COMMENT 'Truncated stderr (<=10000 chars)',
    exit_code    INT          NULL     COMMENT 'Process exit code (-1 = never started/killed)',
    status       VARCHAR(16)  NOT NULL COMMENT 'success / error / timeout / resource_limit',
    duration_ms  BIGINT       NULL     COMMENT 'Wall-clock execution duration',
    timed_out    TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '1 if killed by timeout',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_sbx_thread (thread_id),
    INDEX idx_sbx_user   (user_id),
    INDEX idx_sbx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Sandbox code execution audit log';
