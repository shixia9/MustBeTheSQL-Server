package com.sql.logic.engine.domain.agentic.routing;

/**
 * Query complexity classification levels for adaptive routing.
 * <p>
 * SIMPLE queries skip PlannerAgent + ManagerAgent and go directly to
 * DataScientistAgent. MEDIUM and COMPLEX queries go through the full
 * PlannerAgent → ManagerAgent → Workers → Dashboard pipeline.
 */
public enum ComplexityLevel {
    /** Single SQL can answer, direct DataScientistAgent path. */
    SIMPLE,
    /** Moderate multi-step, standard orchestration path. */
    MEDIUM,
    /** Complex multi-step requiring full pipeline. */
    COMPLEX,
    /** Question is ambiguous, HITL clarification needed. */
    CLARIFY
}
