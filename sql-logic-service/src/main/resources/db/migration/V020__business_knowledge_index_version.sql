-- Apply before deploying the version-aware RAG service.
ALTER TABLE business_knowledge
    ADD COLUMN knowledge_version BIGINT NOT NULL DEFAULT 1 COMMENT 'Monotonic content/index version' AFTER status;

UPDATE business_knowledge SET status = 1 WHERE status IS NULL;

ALTER TABLE business_knowledge
    MODIFY COLUMN status TINYINT(1) NOT NULL DEFAULT 0
    COMMENT '0: pending embedding, 1: active, 2: pending vector deletion';

CREATE INDEX idx_knowledge_retry ON business_knowledge(status, update_time);
