-- V008: Phase C — SUMMARIZE context-window strategy: add summary cache column.
ALTER TABLE conversation ADD COLUMN summary_cache TEXT NULL;
