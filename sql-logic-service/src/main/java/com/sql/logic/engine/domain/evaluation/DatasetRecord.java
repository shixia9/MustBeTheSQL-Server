package com.sql.logic.engine.domain.evaluation;

import java.util.Map;

/**
 * A single record in an evaluation dataset.
 */
public record DatasetRecord(
        String questionId,
        String dbId,
        String question,
        String groundTruthSql,
        String difficulty,
        String schemaDdl,
        Map<String, Object> evidence) {

    public DatasetRecord {
        difficulty = difficulty != null ? difficulty : "moderate";
    }
}
