package com.sql.logic.engine.infrastructure.llm;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.*;

public class JsonStreamParser {

    private final StringBuilder buffer = new StringBuilder();
    private final ObjectMapper mapper = new ObjectMapper();
    private boolean sqlEmitted = false;
    private int explainIndex = 0;
    private String extractedSql = "";

    public String getExtractedSql() {
        return extractedSql;
    }

    public List<String> processChunk(String chunk) {
        List<String> events = new ArrayList<>();
        if (chunk == null || chunk.isEmpty()) {
            return events;
        }
        buffer.append(chunk);

        try {
            JsonFactory factory = new JsonFactory();
            JsonParser parser = factory.createParser(buffer.toString());
            String currentField = null;
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
                }
            }
        } catch (IOException e) {
            // JSON 未完成是正常情况（streaming）
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
            }
            if (json.containsKey("explain")) {
                String explain = String.valueOf(json.get("explain"));
                if (explain.length() > explainIndex) {
                    String delta = explain.substring(explainIndex);
                    events.add(formatEvent("explain", delta));
                }
            }
        } catch (Exception ignored) {}
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