package com.sql.logic.engine.common.dto;

import lombok.Data;

/**
 * Phase 5 — update an existing business knowledge row. All fields except
 * {@code id} are optional (null = keep existing).
 */
@Data
public class BusinessKnowledgeUpdateRequest {
    /** Required: id of the row to update. */
    private Long id;
    private String vectorType;
    private String term;
    private String description;
    private String synonyms;
    private String question;
    private String answer;
}