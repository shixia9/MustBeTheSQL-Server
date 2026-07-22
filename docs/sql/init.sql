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
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    summary_cache TEXT NULL
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
    db_name VARCHAR(100) DEFAULT NULL,
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

-- user-managed business knowledge (glossary + few-shot QA) for RAG.
CREATE TABLE IF NOT EXISTS business_knowledge (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL COMMENT 'Owner of this knowledge row',
    connection_id BIGINT NOT NULL COMMENT 'The user DB connection this knowledge scopes to',
    vector_type VARCHAR(32) NOT NULL COMMENT 'GLOSSARY_KNOWLEDGE | QUESTION_KNOWLEDGE',
    term VARCHAR(255) DEFAULT NULL COMMENT 'Glossary term',
    description TEXT COMMENT 'Glossary description / definition',
    question TEXT COMMENT 'FAQ question',
    answer TEXT COMMENT 'FAQ answer (e.g. reference SQL)',
    synonyms VARCHAR(500) DEFAULT NULL COMMENT 'Glossary synonyms (comma-separated)',
    status TINYINT(1) DEFAULT 1 COMMENT '0: pending re-embed (embedding failed), 1: active',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_user_conn_type (user_id, connection_id, vector_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='User-managed business knowledge (glossary + few-shot QA)';

-- Agent execution history — records each agentic run's summary and per-node steps.
CREATE TABLE IF NOT EXISTS agent_execution (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    connection_id BIGINT DEFAULT NULL,
    schema_name VARCHAR(100) DEFAULT NULL,
    input TEXT NOT NULL COMMENT 'Original user natural language query',
    summary VARCHAR(200) DEFAULT NULL COMMENT 'AI-generated or truncated title for history display',
    status VARCHAR(32) DEFAULT 'COMPLETED' COMMENT 'COMPLETED, ERROR, CANCELLED',
    thread_id VARCHAR(64) DEFAULT NULL COMMENT 'graph-core checkpoint threadId',
    total_tokens INT DEFAULT 0,
    total_duration_ms BIGINT DEFAULT 0,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_time (user_id, create_time DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent execution summary';

CREATE TABLE IF NOT EXISTS agent_execution_step (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    execution_id BIGINT NOT NULL COMMENT 'FK to agent_execution.id',
    node_name VARCHAR(64) NOT NULL COMMENT 'EVIDENCE_RECALL, SCHEMA_LINKING, etc.',
    sequence_no INT NOT NULL COMMENT 'Step ordering within the execution',
    status VARCHAR(32) DEFAULT 'SUCCESS' COMMENT 'SUCCESS, ERROR, SKIPPED',
    duration_ms BIGINT DEFAULT 0,
    output_data JSON DEFAULT NULL COMMENT 'Node-level output payload',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_execution (execution_id, sequence_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent execution per-node detail';

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

ALTER TABLE db_connection_conf ADD COLUMN workspace_id BIGINT DEFAULT NULL AFTER user_id;
ALTER TABLE conversation ADD COLUMN workspace_id BIGINT DEFAULT NULL AFTER user_id;
ALTER TABLE business_knowledge ADD COLUMN workspace_id BIGINT DEFAULT NULL AFTER user_id;
ALTER TABLE agent_execution ADD COLUMN workspace_id BIGINT DEFAULT NULL AFTER user_id;

ALTER TABLE agent_execution ADD COLUMN model_calls INT DEFAULT 0 AFTER total_tokens;
ALTER TABLE agent_execution ADD COLUMN tool_calls INT DEFAULT 0 AFTER model_calls;

ALTER TABLE agent_execution_step ADD COLUMN input_tokens INT DEFAULT 0 AFTER duration_ms;
ALTER TABLE agent_execution_step ADD COLUMN output_tokens INT DEFAULT 0 AFTER input_tokens;
ALTER TABLE agent_execution_step ADD COLUMN latency_ms BIGINT DEFAULT 0 AFTER output_tokens;
ALTER TABLE agent_execution_step ADD COLUMN node_type VARCHAR(32) DEFAULT NULL AFTER latency_ms;
ALTER TABLE agent_execution_step ADD COLUMN input_data JSON DEFAULT NULL AFTER node_type;
ALTER TABLE agent_execution_step ADD COLUMN output_data_json JSON DEFAULT NULL AFTER input_data;


-- ============================================================
-- LLM HA + Memory + Subtasks
-- ============================================================

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='LLM instance call metrics aggregation';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent memory items';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent subtasks from TaskSplit';

-- ============================================================
-- V005: Agent Studio configuration (agent_entity)
-- ============================================================
CREATE TABLE IF NOT EXISTS agent_entity (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT NOT NULL COMMENT '拥有者',
    workspace_id    BIGINT DEFAULT NULL COMMENT '所属工作区 (NULL=用户私有)',
    name            VARCHAR(128) NOT NULL COMMENT 'Agent 名称',
    description     VARCHAR(512) DEFAULT NULL COMMENT '简介',
    avatar          VARCHAR(255) DEFAULT NULL COMMENT '头像 URL 或 emoji',
    system_prompt   TEXT DEFAULT NULL COMMENT '系统提示词 (追加到各节点 prompt)',
    welcome_message VARCHAR(512) DEFAULT NULL COMMENT '欢迎语',
    tools_config    JSON DEFAULT NULL COMMENT '工具开关: {"sql":true,"schema":true,"python":true,"sample":true}',
    rag_config      JSON DEFAULT NULL COMMENT 'RAG 参数: {"topK":5,"scoreThreshold":0.6,"enabled":true}',
    memory_enabled  TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用记忆注入 0/1',
    is_default      TINYINT NOT NULL DEFAULT 0 COMMENT '是否当前用户默认 Agent 0/1',
    status          TINYINT NOT NULL DEFAULT 1 COMMENT '0:停用 1:启用',
    create_time     DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_agent_user (user_id),
    INDEX idx_agent_workspace (workspace_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 配置实体 (Studio)';

INSERT INTO agent_entity (user_id, workspace_id, name, description, system_prompt, welcome_message,
                          tools_config, rag_config, memory_enabled, is_default, status)
SELECT u.id, NULL, '默认数据助手', '开箱即用的 Text2SQL 数据分析助手',
       '你是一位耐心、专业的数据分析助手，面向不熟悉 SQL 的业务人员，用自然语言解释结果。',
       '你好，我可以帮你从数据库中查询和分析数据，请直接用自然语言描述你想知道的信息。',
       JSON_OBJECT('sql', true, 'schema', true, 'python', true, 'sample', true),
       JSON_OBJECT('topK', 5, 'scoreThreshold', 0.6, 'enabled', true),
       1, 1, 1
FROM user_info u
WHERE NOT EXISTS (SELECT 1 FROM agent_entity ae WHERE ae.user_id = u.id);

-- ============================================================
-- Task progress snapshot persistence
-- ============================================================
CREATE TABLE IF NOT EXISTS task_progress_snapshot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    conv_id VARCHAR(64) NOT NULL COMMENT 'Conversation / thread identifier',
    step_number INT NOT NULL COMMENT 'Step number within the conversation',
    action TEXT COMMENT 'Action description (what was done)',
    phase VARCHAR(64) COMMENT 'Execution phase (e.g. SQL_GENERATION, PYTHON_EXECUTION)',
    status VARCHAR(16) NOT NULL DEFAULT 'DONE' COMMENT 'DONE | FAILED',
    snapshot TEXT COMMENT 'Snapshot of the result at this step',
    model_name VARCHAR(100) DEFAULT NULL COMMENT 'LLM model used for this step',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_conv_id (conv_id),
    INDEX idx_conv_step (conv_id, step_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Persisted task progress snapshots for agent workflows';
