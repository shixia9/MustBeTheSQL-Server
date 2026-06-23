package com.sql.logic.engine.domain.agent;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sql.logic.engine.domain.agent.dto.Plan;
import com.sql.logic.engine.domain.agent.dto.PlanStep;

import java.util.List;

/**
 * Shared helpers for reading the current plan step's instruction across the
 * generation nodes (SQL generation, Python generation, analysis). Centralised
 * so the "decode PLAN (JSON string) + index by CURRENT_STEP" logic isn't duplicated.
 */
public final class PlanStepUtil {

    private PlanStepUtil() {}

    /**
     * Resolve the {@code instruction} for the current plan step; fall back to the
     * provided default when no plan is available or the cursor is out of range.
     */
    public static String currentInstruction(OverAllState state, ObjectMapper objectMapper, int currentStep, String fallback) {
        String planJson = state.value(SqlAgentSpec.StateKey.PLAN, "");
        Plan plan = Plan.fromJson(objectMapper, planJson);
        if (plan == null || plan.executionPlan() == null) {
            return fallback;
        }
        List<PlanStep> steps = plan.executionPlan();
        if (currentStep < 1 || currentStep > steps.size()) {
            return fallback;
        }
        PlanStep step = steps.get(currentStep - 1);
        if (step.toolParameters() != null
                && step.toolParameters().instruction() != null
                && !step.toolParameters().instruction().isBlank()) {
            return step.toolParameters().instruction();
        }
        return fallback;
    }
}