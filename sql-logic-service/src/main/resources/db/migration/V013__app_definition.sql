-- V013: Phase 5 Optimization — agent app definition for flow-to-agent binding
CREATE TABLE IF NOT EXISTS agent_app_definition (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(200) NOT NULL COMMENT 'App display name',
    description VARCHAR(500) DEFAULT NULL,
    team_mode VARCHAR(32) NOT NULL DEFAULT 'auto_plan' COMMENT 'single_agent | auto_plan | awel_layout',
    team_context MEDIUMTEXT DEFAULT NULL COMMENT 'JSON: flow reference for awel_layout, or agent selection for auto_plan',
    agent_details MEDIUMTEXT DEFAULT NULL COMMENT 'JSON array: [{agentName, llmStrategy, resources, promptTemplate}]',
    published TINYINT(1) DEFAULT 0 COMMENT '0=draft, 1=published',
    user_id BIGINT NOT NULL,
    workspace_id BIGINT DEFAULT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_app_user (user_id),
    INDEX idx_app_published (published)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent app definitions binding flows and agents';
