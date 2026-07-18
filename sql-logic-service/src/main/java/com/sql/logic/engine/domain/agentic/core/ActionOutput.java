package com.sql.logic.engine.domain.agentic.core;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Result of an Agent's action execution.
 * Carries the action's outcome, structured data, and routing hints for
 * {@code next_speakers} selection (the Agent that should speak next).
 */
public record ActionOutput(
        boolean success,
        String content,
        Map<String, Object> data,
        List<String> nextSpeakers,
        boolean hasRetry
) {
    public ActionOutput {
        data = data != null ? Collections.unmodifiableMap(new HashMap<>(data)) : Map.of();
        nextSpeakers = nextSpeakers != null ? List.copyOf(nextSpeakers) : List.of();
    }

    public static ActionOutput success(String content) {
        return new ActionOutput(true, content, Map.of(), List.of(), false);
    }

    public static ActionOutput success(String content, Map<String, Object> data) {
        return new ActionOutput(true, content, data, List.of(), false);
    }

    public static ActionOutput fail(String content) {
        return new ActionOutput(false, content, Map.of(), List.of(), true);
    }

    public static ActionOutput fail(String content, boolean hasRetry) {
        return new ActionOutput(false, content, Map.of(), List.of(), hasRetry);
    }

    public boolean isExeSuccess() {
        return success;
    }
}
