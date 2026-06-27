package com.sql.logic.engine.domain.agent.dto;

/**
 * DTO for the feasibility assessment node's structured output.
 * <p>
 * To be used in Phase 2+ when FEASIBILITY_ASSESSMENT is added to the graph.
 */
public record FeasibilityResultDTO(
    String conclusion,
    String nextNode
) {}