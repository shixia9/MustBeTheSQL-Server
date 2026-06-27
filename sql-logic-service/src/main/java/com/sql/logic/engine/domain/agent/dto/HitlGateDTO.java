package com.sql.logic.engine.domain.agent.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * DTO for the HITL gate node's structured output.
 * <p>
 * Used with BeanOutputConverter to enforce JSON output from the LLM:
 * <pre>{"needs_review": true|false, "reason": "..."}</pre>
 */
public record HitlGateDTO(
        @JsonProperty("needs_review") Boolean needsReview,
        @JsonProperty("reason") String reason
) {}