package com.sql.logic.engine.domain.agent.dto;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A single step in the multi-step execution plan produced by the Planner node.
 * <p>
 * Mirrors the reference project's `PlanStep` Kotlin data class. The
 * {@code tool_parameters} object is dynamic — only the fields relevant to the
 * chosen {@code tool_to_use} are populated.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PlanStep(
        @JsonProperty("step") int step,
        @JsonProperty("tool_to_use") String toolToUse,
        @JsonProperty("tool_parameters") ToolParameters toolParameters
) {
    /**
     * Dynamic parameters for a plan step.
     * <ul>
     *   <li>{@code instruction} — used by SQL_GENERATE_NODE / PYTHON_GENERATE_NODE</li>
     *   <li>{@code summaryAndRecommendations} — used by REPORT_GENERATOR_NODE</li>
     *   <li>{@code mcpParams} — used by MCP tool steps (natural JSON object, no double-encoding)</li>
     * </ul>
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ToolParameters(
            @JsonProperty("instruction") String instruction,
            @JsonProperty("summary_and_recommendations") String summaryAndRecommendations,
            @JsonProperty("mcp_params") Map<String, Object> mcpParams
    ) {}
}