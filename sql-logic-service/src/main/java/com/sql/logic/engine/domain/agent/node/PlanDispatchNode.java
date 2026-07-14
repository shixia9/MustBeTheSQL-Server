package com.sql.logic.engine.domain.agent.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sql.logic.engine.domain.agent.AgentStateUtil;
import com.sql.logic.engine.domain.agent.AgentToolGate;
import com.sql.logic.engine.domain.agent.SqlAgentSpec;
import com.sql.logic.engine.domain.agent.dto.Plan;
import com.sql.logic.engine.domain.agent.dto.PlanStep;
import com.sql.logic.engine.domain.agent.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plan Dispatch Node (Phase 3) — pure control node, no LLM call.
 * <p>
 * Uses {@code CURRENT_STEP} (1-based) as a cursor into the decoded {@code PLAN}.
 * <ul>
 *   <li>{@code currentStep > steps.size()} → cursor reset, {@code NEXT_NODE = END}</li>
 *   <li>{@code SQL_GENERATE_NODE} → {@code NEXT_NODE = SQL_GENERATION}</li>
 *   <li>{@code REPORT_GENERATOR_NODE} → {@code NEXT_NODE = REPORT}</li>
 *   <li>{@code PYTHON_GENERATE_NODE} → {@code NEXT_NODE = PYTHON_GENERATION} (Phase 4)</li>
 *   <li>plan missing/unparseable → {@code NEXT_NODE = REPORT} (degraded to a flat report)</li>
 * </ul>
 * The fixed {@code PLANNER → PLAN_DISPATCH} edge and the conditional
 * {@code PLAN_DISPATCH → {SQL_GENERATION | REPORT | END}} mapping live in the graph config.
 */
@Component
public class PlanDispatchNode implements NodeAction {

    private static final Logger log = LoggerFactory.getLogger(PlanDispatchNode.class);

    private final ObjectMapper objectMapper;
    private final ToolRegistry toolRegistry;

    public PlanDispatchNode(ObjectMapper objectMapper, ToolRegistry toolRegistry) {
        this.objectMapper = objectMapper;
        this.toolRegistry = toolRegistry;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String planJson = state.value(SqlAgentSpec.StateKey.PLAN, "");
        int currentStep = readInt(state, SqlAgentSpec.StateKey.CURRENT_STEP, 1);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put(SqlAgentSpec.StateKey.CURRENT_STEP, currentStep);  // echo for the controller's event

        Plan plan = Plan.fromJson(objectMapper, planJson);
        if (plan == null || plan.executionPlan() == null || plan.executionPlan().isEmpty()) {
            log.warn("[PlanDispatch] Plan missing/empty — routing to REPORT (degraded).");
            out.put(SqlAgentSpec.StateKey.NEXT_NODE, SqlAgentSpec.Node.REPORT);
            return out;
        }

        List<PlanStep> steps = plan.executionPlan();
        if (currentStep > steps.size()) {
            log.info("[PlanDispatch] All {} steps dispatched — routing to REPORT.", steps.size());
            out.put(SqlAgentSpec.StateKey.CURRENT_STEP, 1);  // reset cursor
            out.put(SqlAgentSpec.StateKey.NEXT_NODE, SqlAgentSpec.Node.REPORT);
            return out;
        }

        PlanStep step = steps.get(currentStep - 1);
        String nextNode = mapToolToNode(step.toolToUse(), state, step);
        log.info("[PlanDispatch] step {}/{} tool={} → {}", currentStep, steps.size(), step.toolToUse(), nextNode);
        out.put(SqlAgentSpec.StateKey.NEXT_NODE, nextNode);
        // For MCP tools, store the tool name and params in state for the executor
        if (SqlAgentSpec.Node.MCP_TOOL_EXECUTOR.equals(nextNode)) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put(SqlAgentSpec.StateKey.MCP_TOOL_NAME, step.toolToUse());
            String inst = step.toolParameters() != null ? step.toolParameters().instruction() : "{}";
            params.put(SqlAgentSpec.StateKey.MCP_TOOL_PARAMS, inst != null ? inst : "{}");
            out.putAll(params);
        }
        return out;
    }

    /**
     * Map the plan's tool name (reference-project vocabulary) onto the actual
     * graph node id. Unknown tools degrade to END.
     */
    private String mapToolToNode(String toolToUse, OverAllState state, PlanStep step) {
        if (toolToUse == null) {
            return SqlAgentSpec.Node.REPORT;
        }
        String key = toolToUse.trim().toUpperCase();
        switch (key) {
            case "SQL_GENERATE_NODE":
                return SqlAgentSpec.Node.SQL_GENERATION;
            case "REPORT_GENERATOR_NODE":
                return SqlAgentSpec.Node.REPORT;
            case "PYTHON_GENERATE_NODE":
                if (!AgentToolGate.isToolEnabled(state, AgentToolGate.TOOL_PYTHON)) {
                    log.warn("[PlanDispatch] PYTHON_GENERATE_NODE requested but python tool is disabled — routing to REPORT instead");
                    return SqlAgentSpec.Node.REPORT;
                }
                return SqlAgentSpec.Node.PYTHON_GENERATION;
            default:
                // check if it's a registered MCP tool (any case-insensitive match)
                if (toolRegistry.isRegistered(toolToUse.trim())) {
                    log.info("[PlanDispatch] Routing '{}' to MCP_TOOL_EXECUTOR", toolToUse);
                    return SqlAgentSpec.Node.MCP_TOOL_EXECUTOR;
                }
                log.warn("[PlanDispatch] Unknown tool_to_use '{}' — routing to REPORT.", toolToUse);
                return SqlAgentSpec.Node.REPORT;
        }
    }

    private int readInt(OverAllState state, String key, int dflt) {
        Object v = state.value(key, (Integer) null);
        if (v == null) {
            return dflt;
        }
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            Long asLong = AgentStateUtil.toLong(v);
            return asLong == null ? dflt : asLong.intValue();
        }
    }
}