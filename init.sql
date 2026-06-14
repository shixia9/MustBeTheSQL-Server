CREATE DATABASE IF NOT EXISTS sql_logic_engine;
USE sql_logic_engine;

CREATE TABLE IF NOT EXISTS user_info (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    password VARCHAR(100) NOT NULL,
    email VARCHAR(100),
    avatar VARCHAR(500),
    status TINYINT(1) DEFAULT 1 COMMENT '0: Banned, 1: Active, 2: Frozen, -1: Cancelled',
    token_quota INT DEFAULT 100 COMMENT 'Remaining AI tokens',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

INSERT IGNORE INTO user_info (id, username, password, email, status, token_quota) VALUES (1, 'admin', 'admin123', 'admin@example.com', 1, 10000);

CREATE TABLE IF NOT EXISTS user_llm_api_key (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL UNIQUE,
    strategy_name VARCHAR(100) NOT NULL DEFAULT 'openAiStrategy',
    base_url VARCHAR(255),
    api_key VARCHAR(255) NOT NULL,
    status TINYINT(1) DEFAULT 1 COMMENT '0: Inactive, 1: Active',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS user_llm_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    config_name VARCHAR(100) NOT NULL COMMENT 'User-facing display label',
    provider_type VARCHAR(50) NOT NULL DEFAULT 'OPENAI_COMPATIBLE' COMMENT 'OPENAI_COMPATIBLE | ANTHROPIC',
    base_url VARCHAR(500) DEFAULT NULL COMMENT 'API base URL, null means provider default',
    api_key VARCHAR(500) NOT NULL COMMENT 'AES-encrypted API key',
    model_name VARCHAR(100) DEFAULT NULL COMMENT 'Specific model to use, e.g. gpt-4o, claude-sonnet-4-6-20250514',
    is_default TINYINT(1) DEFAULT 0 COMMENT '1 = default config for this user',
    status TINYINT(1) DEFAULT 1 COMMENT '0: Inactive, 1: Active',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_user_id (user_id),
    INDEX idx_user_status (user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='User LLM API configurations (multiple per user)';

-- Migrate existing data from user_llm_api_key
INSERT INTO user_llm_config (user_id, config_name, provider_type, base_url, api_key, model_name, is_default, status, create_time, update_time)
SELECT
    user_id,
    'My API Key' AS config_name,
    'OPENAI_COMPATIBLE' AS provider_type,
    base_url,
    api_key,
    'gpt-4o' AS model_name,
    1 AS is_default,
    status,
    create_time,
    update_time
FROM user_llm_api_key WHERE status = 1;

-- Add llm_config_id to query_history for tracking which config generated each query
ALTER TABLE query_history ADD COLUMN llm_config_id BIGINT DEFAULT NULL COMMENT 'FK to user_llm_config.id';
ALTER TABLE query_history ADD INDEX idx_llm_config_id (llm_config_id);

CREATE TABLE IF NOT EXISTS conversation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    llm_strategy_id BIGINT NOT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS conversation_detail (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    user_input TEXT,
    sql_output TEXT,
    execute_result TEXT,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS db_connection_conf (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    db_type VARCHAR(50) NOT NULL,
    host VARCHAR(255) NOT NULL,
    port INT NOT NULL,
    username VARCHAR(100) NOT NULL,
    password VARCHAR(255) NOT NULL,
    db_name VARCHAR(100) NOT NULL,
    is_test TINYINT(1) DEFAULT 0
);

CREATE TABLE IF NOT EXISTS query_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    prompt TEXT,
    connection_id BIGINT,
    database_name VARCHAR(100),
    generated_sql TEXT,
    model_name VARCHAR(50),
    execute_latency BIGINT,
    tokens INT,
    row_count INT,
    cost DECIMAL(10, 4),
    parent_id BIGINT,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    execute_time DATETIME
);

CREATE TABLE IF NOT EXISTS sql_audit_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    connection_id BIGINT NOT NULL,
    client_ip VARCHAR(64),
    sql_script LONGTEXT NOT NULL,
    execute_latency BIGINT,
    status VARCHAR(20) NOT NULL COMMENT 'SUCCESS, FAILED, TIMEOUT',
    error_message TEXT,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
);
