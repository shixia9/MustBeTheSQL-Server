-- ============================================================
-- Agent Studio configuration (V005) — Phase B B4
-- Description:
--   Adds the agent_entity table so users can self-manage Agent configurations
--   (system prompt / welcome message / tool toggles / RAG params / memory switch)
--   from the frontend Studio, instead of editing prompts in code.
-- All config columns nullable / default to preserve backward compatibility:
--   a run with no agent config falls back to the built-in default behaviour.
-- Run: Once per environment.
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
) COMMENT 'Agent 配置实体 (Studio)';

-- Seed one default Agent per existing user so the Studio list is never empty.
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
