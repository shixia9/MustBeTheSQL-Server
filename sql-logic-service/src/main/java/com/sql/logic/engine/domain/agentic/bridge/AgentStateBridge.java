package com.sql.logic.engine.domain.agentic.bridge;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.sql.logic.engine.domain.agent.SqlAgentSpec;
import com.sql.logic.engine.domain.agentic.core.ActionOutput;
import com.sql.logic.engine.domain.agentic.core.AgentMessage;

import java.util.HashMap;
import java.util.Map;

/**
 * Bidirectional bridge between the existing StateGraph {@link OverAllState}
 * and the new Multi-Agent {@link AgentMessage}.
 * <p>
 * This bridge enables Phase 1 agents to coexist with the existing StateGraph:
 * <ul>
 *   <li>{@link #toAgentMessage(OverAllState)} — reads standard state keys
 *       and constructs a typed AgentMessage for the agent pipeline.</li>
 *   <li>{@link #toStateUpdates(AgentMessage)} — extracts action results from
 *       the AgentMessage and maps them back to existing StateKey constants
 *       for downstream graph nodes.</li>
 * </ul>
 * <p>
 * All state key references use {@link SqlAgentSpec.StateKey} constants,
 * ensuring zero coupling to the new framework from the old codebase.
 */
public final class AgentStateBridge {

    private AgentStateBridge() {}

    /**
     * Convert graph state to an AgentMessage for agent consumption.
     */
    public static AgentMessage toAgentMessage(OverAllState state) {
        String input = getString(state, SqlAgentSpec.StateKey.INPUT);
        String rewriteQuery = getString(state, SqlAgentSpec.StateKey.REWRITE_QUERY);
        String userQuestion = (rewriteQuery != null && !rewriteQuery.isBlank())
                ? rewriteQuery : input;

        AgentMessage.Builder builder = AgentMessage.builder()
                .content(userQuestion != null ? userQuestion : "")
                .senderName("StateGraph")
                .senderRole("Graph")

                // Schema context
                .putContext("schemaInfo", getString(state, SqlAgentSpec.StateKey.TABLE_RELATION))
                .putContext("dialect", getString(state, SqlAgentSpec.StateKey.DB_TYPE))
                .putContext("schemaName", getString(state, SqlAgentSpec.StateKey.SCHEMA_NAME))

                // Evidence and memory
                .putContext("evidence", getString(state, SqlAgentSpec.StateKey.EVIDENCE))
                .putContext("userMemory", getString(state, SqlAgentSpec.StateKey.USER_MEMORY))
                .putContext("conversationHistory", getString(state, SqlAgentSpec.StateKey.CONVERSATION_HISTORY))

                // Identity
                .putContext("userId", toLong(state, SqlAgentSpec.StateKey.USER_ID))
                .putContext("connectionId", toLong(state, SqlAgentSpec.StateKey.CONNECTION_ID))
                .putContext("llmConfigId", toLong(state, SqlAgentSpec.StateKey.LLM_CONFIG_ID))
                .putContext("workspaceId", toLong(state, SqlAgentSpec.StateKey.WORKSPACE_ID))
                .putContext("threadId", getString(state, SqlAgentSpec.StateKey.THREAD_ID))
                .putContext("sessionId", getString(state, SqlAgentSpec.StateKey.SESSION_ID))

                // Agent Studio config
                .putContext("agentSystemPrompt", getString(state, SqlAgentSpec.StateKey.AGENT_SYSTEM_PROMPT))
                .putContext("agentMemoryEnabled", getString(state, SqlAgentSpec.StateKey.AGENT_MEMORY_ENABLED))
                .putContext("agentTools", getString(state, SqlAgentSpec.StateKey.AGENT_TOOLS))
                .putContext("agentName", getString(state, SqlAgentSpec.StateKey.AGENT_NAME))

                // Plan context
                .putContext("plan", getString(state, SqlAgentSpec.StateKey.PLAN))
                .putContext("currentStep", getInt(state, SqlAgentSpec.StateKey.CURRENT_STEP))
                .putContext("repairCount", getInt(state, SqlAgentSpec.StateKey.REPAIR_COUNT))

                // Resource info
                .putResourceInfo("tableNames", getString(state, SqlAgentSpec.StateKey.TABLE_NAMES));

        // Inject execution description from the current plan step
        String planJson = getString(state, SqlAgentSpec.StateKey.PLAN);
        if (planJson != null && !planJson.isBlank()) {
            builder.putContext("executionDescription", extractCurrentStepInstruction(planJson,
                    getInt(state, SqlAgentSpec.StateKey.CURRENT_STEP)));
        }

        return builder.build();
    }

    /**
     * Convert an agent's output back to state update map for the graph.
     */
    public static Map<String, Object> toStateUpdates(AgentMessage output) {
        Map<String, Object> updates = new HashMap<>();
        updates.put(SqlAgentSpec.StateKey.SQL_GENERATION_RESULT, output.content());

        ActionOutput report = output.actionReport();
        if (report != null) {
            if (report.success()) {
                updates.put(SqlAgentSpec.StateKey.SQL_EXECUTION_RESULT, report.content());
                updates.put(SqlAgentSpec.StateKey.SQL_ERROR, "");
            } else {
                updates.put(SqlAgentSpec.StateKey.SQL_ERROR, report.content());
            }

            // Propagate additional data
            if (report.data() != null) {
                if (report.data().containsKey("sql")) {
                    updates.put(SqlAgentSpec.StateKey.SQL_GENERATION_RESULT, report.data().get("sql"));
                }
                if (report.data().containsKey("columns")) {
                    updates.put("executionColumns", report.data().get("columns"));
                }
                if (report.data().containsKey("rows")) {
                    updates.put("executionRows", report.data().get("rows"));
                }
                if (report.data().containsKey("rowCount")) {
                    updates.put("executionRowCount", report.data().get("rowCount"));
                }
            }
        }

        updates.put("agentSuccess", output.success());
        return updates;
    }

    // --- Helpers ---

    private static String getString(OverAllState state, String key) {
        return state.value(key).map(Object::toString).orElse(null);
    }

    private static Long toLong(OverAllState state, String key) {
        return state.value(key).map(v -> {
            if (v instanceof Number n) return n.longValue();
            try { return Long.parseLong(v.toString()); } catch (NumberFormatException e) { return null; }
        }).orElse(null);
    }

    private static int getInt(OverAllState state, String key) {
        return state.value(key).map(v -> {
            if (v instanceof Number n) return n.intValue();
            try { return Integer.parseInt(v.toString()); } catch (NumberFormatException e) { return 1; }
        }).orElse(1);
    }

    private static String extractCurrentStepInstruction(String planJson, int currentStep) {
        // Best-effort extraction of the current step's instruction from the Plan JSON.
        // The plan format is: {"execution_plan": [{"step": N, "tool_to_use": "...",
        //   "tool_parameters": {"instruction": "..."}}]}
        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            var node = om.readTree(planJson);
            var steps = node.get("execution_plan");
            if (steps != null && steps.isArray()) {
                for (var step : steps) {
                    if (step.get("step") != null && step.get("step").asInt() == currentStep) {
                        var params = step.get("tool_parameters");
                        if (params != null) {
                            var instr = params.get("instruction");
                            if (instr != null) return instr.asText();
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return "";
    }
}
