package com.sql.logic.engine.domain.agent.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * Multi-step execution plan produced by the Planner node.
 * <p>
 * Mirrors the reference project's `Plan` Kotlin data class. Stored in Agent
 * state as the deserialized object (or a JSON string — see PlannerNode).
 *
 * <pre>
 * {
 *   "thought_process": "...",
 *   "execution_plan": [ { "step": 1, "tool_to_use": "SQL_GENERATE_NODE", "tool_parameters": {"instruction": "..."} }, ... ]
 * }
 * </pre>
 */
public record Plan(
        @JsonProperty("thought_process") String thoughtProcess,
        @JsonProperty("execution_plan") List<PlanStep> executionPlan
) {
    /**
     * Leniently parse a Plan from raw LLM output.
     * Strips markdown code fences (```json ... ```) before deserialization,
     * and tolerates the model wrapping only the object body.
     *
     * @param om the ObjectMapper to use
     * @param raw the raw LLM text (may be fenced, may carry trailing prose)
     * @return the parsed Plan, or {@code null} if parsing fails
     */
    public static Plan fromJson(ObjectMapper om, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String cleaned = raw.trim();
        // Strip ```json ... ``` or ``` ... ``` fences
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceAll("(?s)^```(?:\\w+)?\\s*\\n?", "")
                    .replaceAll("\\n?```\\s*$", "")
                    .trim();
        }
        // If the model prepended commentary, trim to the first '{' ... last '}'
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start >= 0 && end > start) {
            cleaned = cleaned.substring(start, end + 1);
        }
        try {
            return om.readValue(cleaned, Plan.class);
        } catch (Exception e) {
            return null;
        }
    }
}