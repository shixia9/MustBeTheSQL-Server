-- V006: Add conversation_id to agent_execution for conversation-grouped history
ALTER TABLE agent_execution ADD COLUMN conversation_id BIGINT NULL COMMENT 'FK to conversation table' AFTER thread_id;
CREATE INDEX idx_agent_exec_conv ON agent_execution(conversation_id);
