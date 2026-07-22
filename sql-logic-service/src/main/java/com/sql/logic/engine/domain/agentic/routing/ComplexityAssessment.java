package com.sql.logic.engine.domain.agentic.routing;

/**
 * Result of a query complexity assessment by {@link ComplexityRouter}.
 *
 * @param level       the assessed complexity level
 * @param reason      human-readable rationale for the classification
 * @param rawResponse the raw LLM response used for classification
 */
public record ComplexityAssessment(
        ComplexityLevel level,
        String reason,
        String rawResponse
) {
    public boolean isSimple() { return level == ComplexityLevel.SIMPLE; }
    public boolean needsClarification() { return level == ComplexityLevel.CLARIFY; }
}
