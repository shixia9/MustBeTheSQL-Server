-- V014: Prompt / Connector / ScheduledTask CRUD tables
-- User-scoped management modules for the frontend management pages.

-- 1. Prompt — reusable prompt templates per user
CREATE TABLE IF NOT EXISTS prompt_template (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    description VARCHAR(512) NULL,
    status INT DEFAULT 1 COMMENT '0=disabled, 1=active',
    create_time DATETIME,
    update_time DATETIME,
    INDEX idx_prompt_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 2. Connector template — reusable connector definitions per user
CREATE TABLE IF NOT EXISTS connector_template (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    connector_type VARCHAR(64) NOT NULL COMMENT 'e.g. REST, JDBC, FILE, KAFKA',
    config TEXT NULL COMMENT 'JSON configuration',
    description VARCHAR(512) NULL,
    status INT DEFAULT 1 COMMENT '0=disabled, 1=active',
    create_time DATETIME,
    update_time DATETIME,
    INDEX idx_conn_tpl_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 3. Active connector — instantiated connectors bound to a saved DB connection
CREATE TABLE IF NOT EXISTS active_connector (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    template_id BIGINT NULL,
    connection_id BIGINT NULL COMMENT 'FK to db_connection_conf.id',
    name VARCHAR(255) NOT NULL,
    status INT DEFAULT 1 COMMENT '0=disconnected, 1=connected',
    create_time DATETIME,
    update_time DATETIME,
    INDEX idx_active_conn_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 4. Scheduled task — cron-based recurring tasks per user
CREATE TABLE IF NOT EXISTS scheduled_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    cron_expr VARCHAR(128) NOT NULL,
    task_type VARCHAR(64) NULL COMMENT 'e.g. SQL_EXPORT, REPORT, SYNC',
    payload TEXT NULL COMMENT 'JSON task parameters',
    status INT DEFAULT 1 COMMENT '0=paused, 1=running',
    last_run_time DATETIME NULL,
    next_run_time DATETIME NULL,
    create_time DATETIME,
    update_time DATETIME,
    INDEX idx_sched_task_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
