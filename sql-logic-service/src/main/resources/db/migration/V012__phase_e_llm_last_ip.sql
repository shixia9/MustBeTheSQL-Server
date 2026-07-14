-- V012: Phase E — add last_ip column for admin LLM monitoring
ALTER TABLE llm_call_metrics ADD COLUMN IF NOT EXISTS last_ip VARCHAR(45) NULL COMMENT 'last request source IP';
