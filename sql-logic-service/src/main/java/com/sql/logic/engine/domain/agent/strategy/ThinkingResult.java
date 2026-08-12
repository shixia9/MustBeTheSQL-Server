package com.sql.logic.engine.domain.agent.strategy;

/**
 * Result of an LLM call with native thinking mode enabled.
 */
public record ThinkingResult(String content, String reasoning) {

    /** Fallback when thinking mode is not available — content only, empty reasoning. */
    public ThinkingResult(String content) {
        this(content, "");
    }
}
