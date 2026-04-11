package com.sql.logic.engine.infrastructure.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class JsonStreamParser {

    private final StringBuilder buffer = new StringBuilder();
    private int explainEmittedIndex = -1;
    private boolean isFallback = false;
    private boolean sqlEmitted = false;
    private boolean isFirstChunk = true;
    private final ObjectMapper mapper = new ObjectMapper();

    public List<String> processChunk(String chunk) {
        List<String> events = new ArrayList<>();
        if (chunk == null || chunk.isEmpty()) {
            return events;
        }
        
        buffer.append(chunk);
        String current = buffer.toString();

        if (isFirstChunk) {
            String trimmed = current.trim();
            if (!trimmed.isEmpty()) {
                isFirstChunk = false;
                if (!trimmed.startsWith("{") && !trimmed.startsWith("`")) {
                    isFallback = true;
                }
            }
        }

        if (isFallback) {
            events.add(formatEvent("explain", chunk));
            return events;
        }

        // 1. Try to incrementally extract "explain"
        int explainKeyIdx = current.indexOf("\"explain\"");
        if (explainKeyIdx != -1) {
            int colonIdx = current.indexOf(":", explainKeyIdx);
            if (colonIdx != -1) {
                int quoteIdx = current.indexOf("\"", colonIdx);
                if (quoteIdx != -1) {
                    int start = quoteIdx + 1;
                    if (explainEmittedIndex == -1) {
                        explainEmittedIndex = start;
                    }
                    
                    int end = findStringEnd(current, start);
                    
                    if (end != -1) {
                        if (explainEmittedIndex < end) {
                            String delta = current.substring(explainEmittedIndex, end);
                            if (!delta.isEmpty()) {
                                events.add(formatEvent("explain", unescape(delta)));
                            }
                            explainEmittedIndex = end;
                        }
                    } else {
                        int to = current.length() - 1;
                        if (explainEmittedIndex < to) {
                            String delta = current.substring(explainEmittedIndex, to);
                            if (!delta.endsWith("\\")) {
                                events.add(formatEvent("explain", unescape(delta)));
                                explainEmittedIndex = to;
                            }
                        }
                    }
                }
            }
        }

        // 2. Try to extract "sql" as a whole block
        if (!sqlEmitted) {
            int sqlKeyIdx = current.indexOf("\"sql\"");
            if (sqlKeyIdx != -1) {
                int colonIdx = current.indexOf(":", sqlKeyIdx);
                if (colonIdx != -1) {
                    int quoteIdx = current.indexOf("\"", colonIdx);
                    if (quoteIdx != -1) {
                        int start = quoteIdx + 1;
                        int end = findStringEnd(current, start);
                        if (end != -1) {
                            String sql = current.substring(start, end);
                            events.add(formatEvent("sql", unescape(sql)));
                            sqlEmitted = true;
                        }
                    }
                }
            }
        }

        return events;
    }

    public List<String> processComplete() {
        List<String> events = new ArrayList<>();
        if (!isFallback && explainEmittedIndex == -1 && !sqlEmitted) {
            events.add(formatEvent("explain", buffer.toString()));
        }
        return events;
    }

    private int findStringEnd(String text, int start) {
        for (int i = start; i < text.length(); i++) {
            if (text.charAt(i) == '"') {
                int backslashes = 0;
                int j = i - 1;
                while (j >= 0 && text.charAt(j) == '\\') {
                    backslashes++;
                    j--;
                }
                if (backslashes % 2 == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private String formatEvent(String type, String content) {
        Map<String, String> event = new HashMap<>();
        event.put("type", type);
        event.put("content", content);
        try {
            return mapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            return "{\"type\":\"error\",\"content\":\"\"}";
        }
    }

    private String unescape(String text) {
        return text.replace("\\n", "\n")
                   .replace("\\\"", "\"")
                   .replace("\\\\", "\\")
                   .replace("\\t", "\t")
                   .replace("\\r", "\r");
    }
}
