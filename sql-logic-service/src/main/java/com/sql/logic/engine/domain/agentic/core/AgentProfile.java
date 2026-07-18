package com.sql.logic.engine.domain.agentic.core;

import java.util.List;

/**
 * Declares who an Agent IS — its identity, mission, and behavioral constraints.
 * Used by {@link ConversableAgent#buildSystemPrompt} to render the system prompt
 * that governs the agent's LLM reasoning.
 */
public record AgentProfile(
        String name,
        String role,
        String goal,
        List<String> constraints,
        String description
) {
    public AgentProfile {
        constraints = constraints != null ? List.copyOf(constraints) : List.of();
    }
}
