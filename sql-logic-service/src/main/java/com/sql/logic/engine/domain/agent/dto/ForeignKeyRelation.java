package com.sql.logic.engine.domain.agent.dto;

import lombok.Data;

/**
 * Represents a single foreign key relationship between two tables.
 * <p>
 * Extracted via INFORMATION_SCHEMA queries, capturing the source column
 * (the FK column) and the target column (the referenced column).
 * Used by SchemaRelationService and SchemaLinkingNode to build
 * FK-aware schema context for LLM prompts.
 */
@Data
public class ForeignKeyRelation {
    private String sourceTable;
    private String sourceColumn;
    private String targetTable;
    private String targetColumn;

    public ForeignKeyRelation() {}

    public ForeignKeyRelation(String sourceTable, String sourceColumn,
                              String targetTable, String targetColumn) {
        this.sourceTable = sourceTable;
        this.sourceColumn = sourceColumn;
        this.targetTable = targetTable;
        this.targetColumn = targetColumn;
    }

    /**
     * Render as a foreign key expression string.
     * Format: sourceTable.sourceColumn = targetTable.targetColumn
     */
    public String toExpression() {
        return sourceTable + "." + sourceColumn + " = " + targetTable + "." + targetColumn;
    }
}