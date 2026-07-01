-- ============================================================
-- Phase A Migration: Workspace + Trace Extensions (V001)
-- Description: Adds multi-tenant workspace tables and trace
--   columns to support Phase A industrial upgrade.
-- Run: Once per environment. Idempotent (IF NOT EXISTS).
-- ============================================================

-- 1. workspace table
CREATE TABLE IF NOT EXISTS workspace (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL COMMENT 'Workspace display name',
    description VARCHAR(500) DEFAULT NULL COMMENT 'Optional description',
    owner_id BIGINT NOT NULL COMMENT 'FK to user_info.id - creator/owner',
    status TINYINT(1) DEFAULT 1 COMMENT '0: inactive, 1: active',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_owner (owner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Multi-tenant workspaces';

-- 2. workspace_member table
CREATE TABLE IF NOT EXISTS workspace_member (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    workspace_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role VARCHAR(20) NOT NULL DEFAULT 'MEMBER' COMMENT 'OWNER | ADMIN | MEMBER | VIEWER',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_workspace_user (workspace_id, user_id),
    INDEX idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Workspace membership with role-based access';

-- 3. Add workspace_id to affected tables
ALTER TABLE db_connection_conf ADD COLUMN workspace_id BIGINT DEFAULT NULL AFTER user_id;
ALTER TABLE conversation ADD COLUMN workspace_id BIGINT DEFAULT NULL AFTER user_id;
ALTER TABLE business_knowledge ADD COLUMN workspace_id BIGINT DEFAULT NULL AFTER user_id;
ALTER TABLE agent_execution ADD COLUMN workspace_id BIGINT DEFAULT NULL AFTER user_id;

-- 4. Indexes for workspace-scoped queries
CREATE INDEX idx_conn_workspace ON db_connection_conf(workspace_id);
CREATE INDEX idx_conv_workspace ON conversation(workspace_id);
CREATE INDEX idx_know_workspace ON business_knowledge(workspace_id);
CREATE INDEX idx_exec_workspace ON agent_execution(workspace_id);

-- 5. Extend agent_execution for trace
ALTER TABLE agent_execution ADD COLUMN model_calls INT DEFAULT 0 AFTER total_tokens;
ALTER TABLE agent_execution ADD COLUMN tool_calls INT DEFAULT 0 AFTER model_calls;

-- 6. Extend agent_execution_step for trace
ALTER TABLE agent_execution_step ADD COLUMN input_tokens INT DEFAULT 0 AFTER duration_ms;
ALTER TABLE agent_execution_step ADD COLUMN output_tokens INT DEFAULT 0 AFTER input_tokens;
ALTER TABLE agent_execution_step ADD COLUMN latency_ms BIGINT DEFAULT 0 AFTER output_tokens;
ALTER TABLE agent_execution_step ADD COLUMN node_type VARCHAR(32) DEFAULT NULL AFTER latency_ms;
ALTER TABLE agent_execution_step ADD COLUMN input_data JSON DEFAULT NULL AFTER node_type;
ALTER TABLE agent_execution_step ADD COLUMN output_data_json JSON DEFAULT NULL AFTER input_data;

