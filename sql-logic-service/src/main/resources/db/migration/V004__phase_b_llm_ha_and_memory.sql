-- ============================================================
-- LLM HA + Memory + Subtasks (V004)
-- Description:
--   1) Extend user_llm_config with strategy/fallback/circuit state.
--   2) Add llm_call_metrics for per-instance call aggregation.
--   3) Add memory_item for the Agent memory system.
--   4) Add agent_subtask for the TaskSplit workflow.
-- All columns nullable / default to preserve backward compatibility.
-- Run: Once per environment.
-- ============================================================

-- 1) user_llm_config 扩展（向后兼容，全部 nullable）
ALTER TABLE user_llm_config
    ADD COLUMN strategy_type VARCHAR(32) DEFAULT NULL
        COMMENT 'LOCAL | ROUND_ROBIN | LATENCY_FIRST | SUCCESS_RATE_FIRST | SMART; null=legacy direct call',
    ADD COLUMN fallback_chain JSON DEFAULT NULL
        COMMENT '降级链: [configId, ...]; 主实例失败时按序尝试',
    ADD COLUMN circuit_state VARCHAR(16) NOT NULL DEFAULT 'CLOSED'
        COMMENT 'CLOSED | OPEN | HALF_OPEN',
    ADD COLUMN circuit_opened_at DATETIME DEFAULT NULL,
    ADD COLUMN last_success_at DATETIME DEFAULT NULL,
    ADD COLUMN last_failure_at DATETIME DEFAULT NULL,
    ADD COLUMN consecutive_failures INT NOT NULL DEFAULT 0;

-- 2) 调用指标表（按分钟时间窗聚合，参考 API-Premium-Gateway api_instance_metrics）
CREATE TABLE IF NOT EXISTS llm_call_metrics (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    config_id BIGINT NOT NULL COMMENT 'FK user_llm_config.id; 0=系统默认',
    user_id BIGINT DEFAULT NULL,
    window_start DATETIME NOT NULL COMMENT '按分钟截断的时间窗',
    success_count INT NOT NULL DEFAULT 0,
    failure_count INT NOT NULL DEFAULT 0,
    total_latency_ms BIGINT NOT NULL DEFAULT 0,
    total_input_tokens INT NOT NULL DEFAULT 0,
    total_output_tokens INT NOT NULL DEFAULT 0,
    last_reported_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_config_window (config_id, window_start),
    INDEX idx_user_window (user_id, window_start)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Phase B - LLM instance call metrics aggregation';

-- 3) 记忆系统表
CREATE TABLE IF NOT EXISTS memory_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    workspace_id BIGINT DEFAULT NULL,
    type VARCHAR(16) NOT NULL COMMENT 'PROFILE | TASK | FACT | EPISODIC',
    content TEXT NOT NULL,
    importance DECIMAL(4,3) NOT NULL DEFAULT 0.500,
    tags JSON DEFAULT NULL,
    source_session_id VARCHAR(64) DEFAULT NULL COMMENT '来源 agent threadId',
    dedupe_hash CHAR(64) NOT NULL COMMENT 'SHA-256(normalized content) for dedup',
    status TINYINT(1) NOT NULL DEFAULT 1 COMMENT '1=active, 0=archived',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_hash (user_id, dedupe_hash),
    INDEX idx_user_type (user_id, type, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Phase B - Agent memory items';

-- 4) 子任务表（状态机升级）
CREATE TABLE IF NOT EXISTS agent_subtask (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    thread_id VARCHAR(64) NOT NULL COMMENT '关联 agent_execution thread_id',
    parent_thread_id VARCHAR(64) DEFAULT NULL COMMENT '父任务 threadId',
    user_id BIGINT NOT NULL,
    workspace_id BIGINT DEFAULT NULL,
    seq INT NOT NULL COMMENT '子任务序号（从 1 开始）',
    instruction TEXT NOT NULL COMMENT '子任务描述',
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING | RUNNING | SUCCESS | FAILED | SKIPPED',
    result JSON DEFAULT NULL COMMENT '子任务执行结果摘要',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_thread (thread_id),
    INDEX idx_parent (parent_thread_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Phase B - Agent subtasks from TaskSplit';