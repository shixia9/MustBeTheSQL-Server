-- V007: Add agent_id to memory_item and agent_execution for agent-level isolation
ALTER TABLE memory_item ADD COLUMN agent_id BIGINT NULL COMMENT 'FK to agent_entity' AFTER workspace_id;
ALTER TABLE agent_execution ADD COLUMN agent_id BIGINT NULL COMMENT 'FK to agent_entity' AFTER workspace_id;
CREATE INDEX idx_memory_agent ON memory_item(agent_id);
CREATE INDEX idx_agent_exec_agent ON agent_execution(agent_id);
