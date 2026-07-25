package com.sql.logic.engine.domain.evaluation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.*;

/**
 * Loads evaluation datasets from JSON files.
 * Supports:
 * - BIRD format: [{"question_id":..., "db_id":..., "question":..., "SQL":..., "difficulty":...}]
 * - Spider format: [{"question":..., "query":..., "db_id":..., "difficulty":...}]
 * - Simple format: [{"question":..., "groundTruthSql":..., "difficulty":...}]
 */
public class DatasetLoader {

    private static final Logger log = LoggerFactory.getLogger(DatasetLoader.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public List<DatasetRecord> load(InputStream inputStream) {
        try {
            List<Map<String, Object>> raw = objectMapper.readValue(inputStream,
                    new TypeReference<List<Map<String, Object>>>() {});
            return raw.stream()
                    .map(this::parseRecord)
                    .filter(Objects::nonNull)
                    .toList();
        } catch (Exception e) {
            log.error("Failed to load dataset", e);
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private DatasetRecord parseRecord(Map<String, Object> entry) {
        String questionId = getString(entry, "question_id", getString(entry, "id", UUID.randomUUID().toString().substring(0, 8)));
        String dbId = getString(entry, "db_id", "default");
        String question = getString(entry, "question", "");
        // BIRD format uses "SQL", Spider uses "query", simple uses "groundTruthSql"
        String sql = getString(entry, "SQL",
                getString(entry, "query",
                        getString(entry, "groundTruthSql", "")));
        String difficulty = getString(entry, "difficulty", "moderate");
        String schemaDdl = getString(entry, "schemaDdl", getString(entry, "schema", ""));
        Map<String, Object> evidence = (Map<String, Object>) entry.getOrDefault("evidence", Map.of());

        if (question.isBlank() || sql.isBlank()) {
            log.warn("Skipping record with empty question or SQL: id={}", questionId);
            return null;
        }

        return new DatasetRecord(questionId, dbId, question, sql, difficulty, schemaDdl, evidence);
    }

    private String getString(Map<String, Object> map, String key, String defaultValue) {
        Object value = map.get(key);
        return value != null ? value.toString() : defaultValue;
    }
}
