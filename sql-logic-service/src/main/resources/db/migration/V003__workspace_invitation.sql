-- ============================================================
-- Phase A Migration: Workspace Invitation Links (V003)
-- Description: Adds workspace_invitation table to support
--   shareable invitation links with token-based joining.
-- Run: Once per environment. Idempotent (IF NOT EXISTS).
-- ============================================================

CREATE TABLE IF NOT EXISTS workspace_invitation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    workspace_id BIGINT NOT NULL COMMENT 'FK to workspace.id',
    creator_id BIGINT NOT NULL COMMENT 'FK to user_info.id - who created the invitation',
    token VARCHAR(64) NOT NULL COMMENT 'Unique invitation token',
    role VARCHAR(20) NOT NULL DEFAULT 'MEMBER' COMMENT 'Role granted on accept: ADMIN | MEMBER | VIEWER',
    expires_at DATETIME NOT NULL COMMENT 'Invitation link expiration time',
    max_uses INT DEFAULT NULL COMMENT 'Maximum number of uses (NULL = unlimited)',
    use_count INT DEFAULT 0 COMMENT 'Number of times this invitation has been used',
    is_active TINYINT(1) DEFAULT 1 COMMENT '0: revoked, 1: active',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_token (token),
    INDEX idx_workspace (workspace_id),
    INDEX idx_creator (creator_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Workspace invitation links for sharing via URL';
