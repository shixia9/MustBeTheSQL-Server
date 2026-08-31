package com.sql.logic.engine.domain.agent.core;

/**
 * Bounds large textual values copied into the Redis-backed graph checkpoint.
 * Structured execution data is handled by its owning action; this guard covers
 * the unbounded text fields that commonly contain schema, history, prompts or
 * model output.
 */
public final class CheckpointValueLimiter {

    public static final int MAX_TEXT_CHARS = 128 * 1024;
    private static final String TRUNCATION_MARKER = "\n...[checkpoint value truncated]";

    private CheckpointValueLimiter() {}

    public static String limit(String value) {
        if (value == null || value.length() <= MAX_TEXT_CHARS) return value;
        int contentLength = Math.max(0, MAX_TEXT_CHARS - TRUNCATION_MARKER.length());
        return value.substring(0, contentLength) + TRUNCATION_MARKER;
    }
}
