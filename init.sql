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
