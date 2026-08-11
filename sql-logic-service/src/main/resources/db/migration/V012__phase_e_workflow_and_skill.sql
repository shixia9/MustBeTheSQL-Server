-- V012: Phase 5 — workflow DSL, skill persistence, evaluation, and db_connection_conf filePath
-- ============================================================================

-- 1. Add filePath column for file-based databases (DuckDB, SQLite, CSV)
ALTER TABLE db_connection_conf ADD COLUMN IF NOT EXISTS file_path VARCHAR(500) DEFAULT NULL COMMENT 'File path for DuckDB/SQLite/CSV data sources';

-- 2. Workflow definition — persists the JSON DSL {nodes, edges}
CREATE TABLE IF NOT EXISTS workflow_definition (
    id VARCHAR(32) PRIMARY KEY COMMENT 'UUID short-id for the workflow',
    name VARCHAR(200) NOT NULL COMMENT 'Human-readable workflow name',
    description VARCHAR(500) DEFAULT NULL,
    user_id BIGINT NOT NULL COMMENT 'Owner of this workflow',
    workspace_id BIGINT DEFAULT NULL,
    version VARCHAR(20) NOT NULL DEFAULT '1.0',
    config_json MEDIUMTEXT NOT NULL COMMENT 'Full JSON: {version, name, variables, nodes, edges}',
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT | ACTIVE | ARCHIVED',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_wf_user (user_id),
    INDEX idx_wf_workspace (workspace_id),
    INDEX idx_wf_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Workflow DSL definitions (JSON nodes/edges)';

-- 3. Skill definition — persists user-created skills beyond the 3 built-ins
CREATE TABLE IF NOT EXISTS skill_definition (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE COMMENT 'Unique skill identifier (slug)',
    description VARCHAR(500) NOT NULL,
    category VARCHAR(50) NOT NULL DEFAULT 'general' COMMENT 'analysis | generation | visualization | data-engineering | general',
    version VARCHAR(20) NOT NULL DEFAULT '1.0.0',
    prompt_template MEDIUMTEXT NOT NULL COMMENT 'System prompt fragment injected into Agent',
    required_tools JSON DEFAULT NULL COMMENT '["sql_generation", "python_analysis"]',
    required_knowledge JSON DEFAULT NULL COMMENT '["sales_metrics", "kpi_definitions"]',
    tags JSON DEFAULT NULL COMMENT '["sales", "revenue"] for Hub discovery',
    config_json JSON DEFAULT NULL COMMENT 'Arbitrary key-value config map',
    is_public TINYINT(1) DEFAULT 0 COMMENT '1 = visible in Skill Hub',
    author_id BIGINT DEFAULT NULL COMMENT 'user_id of creator',
    workspace_id BIGINT DEFAULT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_skill_category (category),
    INDEX idx_skill_public (is_public),
    INDEX idx_skill_author (author_id),
    INDEX idx_skill_workspace (workspace_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='User-created skill definitions for Skill Hub';

-- 4. Skill embeddings — pgvector-based semantic search for skill matching
CREATE TABLE IF NOT EXISTS skill_embedding (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    skill_name VARCHAR(100) NOT NULL COMMENT 'References skill_definition.name or built-in skill name',
    embedding_vector JSON NOT NULL COMMENT 'float[] vector for cosine similarity search',
    model_name VARCHAR(50) DEFAULT 'ngram-hash' COMMENT 'Which embedding model produced this vector',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_skill_embedding_name (skill_name),
    INDEX idx_embedding_skill (skill_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Skill embeddings for semantic matching';

-- Note: for pgvector-enabled deployments, replace embedding_vector JSON with:
-- embedding_vector vector(1536) and add: CREATE INDEX ON skill_embedding USING ivfflat (embedding_vector vector_cosine_ops);

-- 5. Evaluation task — tracks BIRD/Spider benchmark runs
CREATE TABLE IF NOT EXISTS evaluation_task (
    id VARCHAR(32) PRIMARY KEY COMMENT 'UUID short-id',
    dataset_name VARCHAR(200) NOT NULL COMMENT 'Name of uploaded dataset file',
    total_records INT NOT NULL DEFAULT 0,
    completed_records INT NOT NULL DEFAULT 0,
    parallel_num INT NOT NULL DEFAULT 4,
    status VARCHAR(20) NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING | COMPLETED | FAILED',
    report_json MEDIUMTEXT DEFAULT NULL COMMENT 'Full EvaluationReport serialized as JSON',
    error_message TEXT DEFAULT NULL,
    elapsed_ms BIGINT DEFAULT 0,
    user_id BIGINT NOT NULL,
    workspace_id BIGINT DEFAULT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_eval_user (user_id),
    INDEX idx_eval_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Evaluation tasks for BIRD/Spider benchmark runs';

-- DML: Seed skill_definition with the 3 built-in skills
INSERT IGNORE INTO skill_definition (name, description, category, version, prompt_template, required_tools, required_knowledge, tags, is_public, author_id)
VALUES
('sales-analysis', '销售数据分析: 分析销售额、订单量、客单价等核心指标', 'analysis', '1.0.0',
 'When analyzing sales data:\n1. Start with overall metrics (total revenue, order count, average order value)\n2. Break down by dimensions (time, region, product category, channel)\n3. Compare against previous period for trend analysis\n4. Identify top/bottom performers\n5. Use appropriate aggregations (SUM, AVG, COUNT DISTINCT)',
 '["sql_generation", "sql_execution"]', '["sales_schema", "revenue_metrics"]', '["sales", "revenue", "gmv"]', 1, NULL),

('user-retention', '用户留存分析: 分析用户留存率、流失点、留存曲线', 'analysis', '1.0.0',
 'When analyzing user retention:\n1. Define the cohort by first action date\n2. Calculate Day-N retention (N=1,3,7,14,30)\n3. Identify drop-off points in the user journey\n4. Segment by acquisition channel or user attributes\n5. Use window functions for cohort analysis (LAG, LEAD, ROW_NUMBER)',
 '["sql_generation", "sql_execution", "python_analysis"]', '["user_schema", "retention_metrics"]', '["retention", "cohort", "user"]', 1, NULL),

('anomaly-detection', '异常检测: 识别数据中的异常值、突变点和异常模式', 'analysis', '1.0.0',
 'When detecting anomalies:\n1. Calculate baseline statistics (mean, stddev, percentiles)\n2. Use z-score or IQR methods for outlier detection\n3. Compare current vs historical trends\n4. Flag values exceeding 2 standard deviations\n5. Provide context on why flagged values are anomalous',
 '["sql_generation", "sql_execution", "python_analysis"]', '["statistical_functions"]', '["anomaly", "outlier", "statistics"]', 1, NULL);
