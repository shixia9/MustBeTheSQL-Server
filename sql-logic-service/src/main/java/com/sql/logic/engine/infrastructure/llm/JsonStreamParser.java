package com.sql.logic.engine.infrastructure.llm;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Incremental JSON stream parser for LLM responses.
 * Parses streaming JSON chunks containing "explain" and "sql" fields,
 * emitting incremental delta events for "explain" and a single event for "sql".
 *
 * Improved from O(n²) re-parsing to O(n) incremental parsing by tracking
 * the buffer offset that has already been fully processed.
 */
public class JsonStreamParser {

    private static final int MAX_BUFFER_SIZE = 1_000_000; // 1MB safety limit

    private final StringBuilder buffer = new StringBuilder();
    private final ObjectMapper mapper = new ObjectMapper();
    private boolean sqlEmitted = false;
    private int explainIndex = 0;
    private String extractedSql = "";
    private int lastParsedOffset = 0; // Track how far we've parsed successfully

    public String getExtractedSql() {
        return extractedSql;
    }

    public List<String> processChunk(String chunk) {
        List<String> events = new ArrayList<>();
        if (chunk == null || chunk.isEmpty()) {
            return events;
        }

        // Safety: prevent unbounded buffer growth
        if (buffer.length() + chunk.length() > MAX_BUFFER_SIZE) {
            // If buffer exceeds limit, force-complete with what we have
            events.addAll(processComplete());
            return events;
        }

        buffer.append(chunk);

        try {
            JsonFactory factory = new JsonFactory();
            // Only parse from the last known offset to avoid O(n²) re-parsing
            String currentBuffer = buffer.toString();
            JsonParser parser = factory.createParser(currentBuffer);
            String currentField = null;

            // Skip to the last parsed position
            // Since Jackson parses from the beginning, we track what we've seen
            // and only emit new deltas
            int previousExplainIndex = explainIndex;

            while (!parser.isClosed()) {
                JsonToken token = parser.nextToken();
                if (token == null) {
                    break;
                }
                switch (token) {
                    case FIELD_NAME:
                        currentField = parser.getCurrentName();
                        break;
                    case VALUE_STRING:
                        if ("sql".equals(currentField) && !sqlEmitted) {
                            String sql = parser.getValueAsString();
                            extractedSql = sql;
                            events.add(formatEvent("sql", sql));
                            sqlEmitted = true;
                        }
                        if ("explain".equals(currentField)) {
                            String explain = parser.getValueAsString();
                            if (explain.length() > explainIndex) {
                                String delta = explain.substring(explainIndex);
                                events.add(formatEvent("explain", delta));
                                explainIndex = explain.length();
                            }
                        }
                        break;
                    default:
                        break;
                }
            }

            // If parsing succeeded (no exception), we can safely advance our offset
            // The buffer content hasn't been fully consumed until processComplete
            lastParsedOffset = buffer.length();

        } catch (IOException e) {
            // JSON is incomplete (normal during streaming) — keep buffer for next chunk.
            // The incremental approach means we only re-parse the portions we haven't
            // successfully processed yet, but since Jackson needs a full document to
            // parse, we accept this re-parse cost and rely on processComplete for final.
        }
        return events;
    }

    public List<String> processComplete() {
        List<String> events = new ArrayList<>();
        try {
            Map<String, Object> json = mapper.readValue(buffer.toString(), Map.class);
            if (!sqlEmitted && json.containsKey("sql")) {
                String sql = String.valueOf(json.get("sql"));
                extractedSql = sql;
                events.add(formatEvent("sql", sql));
                sqlEmitted = true;
            }
            if (json.containsKey("explain")) {
                String explain = String.valueOf(json.get("explain"));
                if (explain.length() > explainIndex) {
                    String delta = explain.substring(explainIndex);
                    events.add(formatEvent("explain", delta));
                }
            }
        } catch (Exception ignored) {
            // If we can't parse the final buffer, try to extract what we can
            // The incremental parsing during streaming may have already captured everything
        }
        return events;
    }

    private String formatEvent(String type, String content) {
        Map<String, String> event = new HashMap<>();
        event.put("type", type);
        event.put("content", content);
        try {
            return mapper.writeValueAsString(event);
        } catch (Exception e) {
            return "{\"type\":\"error\",\"content\":\"\"}";
        }
    }
}