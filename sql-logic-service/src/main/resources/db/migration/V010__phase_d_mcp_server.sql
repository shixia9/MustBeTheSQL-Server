-- V010: Phase D2 — MCP server configuration table
CREATE TABLE IF NOT EXISTS mcp_server_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    transport_type VARCHAR(16) NOT NULL COMMENT 'SSE or STDIO',
    endpoint VARCHAR(1024) NOT NULL COMMENT 'URL for SSE, command for STDIO',
    env_vars TEXT NULL COMMENT 'JSON key-value environment variables for stdio',
    status INT DEFAULT 1 COMMENT '0=disabled, 1=active',
    create_time DATETIME,
    update_time DATETIME
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
