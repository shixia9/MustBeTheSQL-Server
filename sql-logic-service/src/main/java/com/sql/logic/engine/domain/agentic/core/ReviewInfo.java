package com.sql.logic.engine.domain.agentic.core;

/**
 * Result of the {@code review()} stage in the generate_reply pipeline.
 * Carries the reviewer's approval decision and optional feedback.
 */
public record ReviewInfo(
        boolean approved,
        String comments
) {
    public static final ReviewInfo APPROVED = new ReviewInfo(true, "");

    public static ReviewInfo reject(String reason) {
        return new ReviewInfo(false, reason != null ? reason : "");
    }
}
