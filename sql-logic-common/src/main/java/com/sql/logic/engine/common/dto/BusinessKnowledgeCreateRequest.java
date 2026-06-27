package com.sql.logic.engine.common.dto;

import lombok.Data;

/**
 * Phase 5 — create/update a business knowledge row (glossary term or few-shot QA).
 * {@code vectorType} drives which RAG channel the row feeds:
 * GLOSSARY_KNOWLEDGE (fill term + description + synonyms) or
 * QUESTION_KNOWLEDGE (fill question + answer).
 */
@Data
public class BusinessKnowledgeCreateRequest {
    /** Required connectionId this knowledge scopes to (must belong to the user). */
    private Long connectionId;
    /** Required: GLOSSARY_KNOWLEDGE | QUESTION_KNOWLEDGE */
    private String vectorType;
    /** Glossary rows. */
    private String term;
    private String description;
    private String synonyms;
    /** Question rows. */
    private String question;
    private String answer;
}