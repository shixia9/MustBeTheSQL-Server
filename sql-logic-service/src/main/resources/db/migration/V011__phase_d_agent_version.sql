-- V011: Phase D3 — agent version management
CREATE TABLE IF NOT EXISTS agent_version (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    agent_id BIGINT NOT NULL,
    version_number INT NOT NULL COMMENT 'sequential version number per agent',
    snapshot_json MEDIUMTEXT NOT NULL COMMENT 'full JSON snapshot of agent config at publish time',
    published_by BIGINT NOT NULL COMMENT 'user_id who published',
    publish_time DATETIME NOT NULL,
    INDEX idx_agent_version (agent_id, version_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
