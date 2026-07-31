-- V015: lightweight Skill table (packaged prompt templates bound to tools)
-- Note: spec named this V011, but V011__phase_d_agent_version.sql already exists,
-- so the next free Flyway version (V015) is used to avoid a version collision.
CREATE TABLE IF NOT EXISTS skill (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL COMMENT 'Owner of the skill (for private skills)',
    name VARCHAR(255) NOT NULL COMMENT 'Skill name shown in the "/" palette',
    description VARCHAR(512) DEFAULT NULL COMMENT 'Short human-readable summary',
    prompt_template TEXT NOT NULL COMMENT 'Prompt template with ${var} placeholders',
    bind_tools JSON DEFAULT NULL COMMENT 'JSON array of bound tool names, e.g. ["sql_generation"]',
    visibility VARCHAR(32) NOT NULL DEFAULT 'private' COMMENT 'public | private',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '1=active, 0=archived (soft delete)',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Lightweight skill = packaged prompt template optionally bound to tools';
