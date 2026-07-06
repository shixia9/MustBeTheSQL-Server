package com.sql.logic.engine.domain.agent.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sql.logic.engine.domain.agent.AgentStateUtil;
import com.sql.logic.engine.domain.agent.SqlAgentSpec;
import com.sql.logic.engine.domain.agent.dto.Plan;
import com.sql.logic.engine.domain.agent.dto.PlanStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Task Dispatch Node (Phase B) — pure control node, no LLM call.
 * <p>
 * Uses {@code CURRENT_SUBTASK} (1-based) as a cursor into the decoded
 * {@code SUBTASKS} JSON array. When all subtasks are dispatched, routes
 * to {@code SUMMARIZE}; otherwise routes to {@code SQL_GENERATION} and
 * injects a synthetic single-step {@code PLAN} carrying the current
 * subtask's instruction so downstream SQL/Python nodes can execute it.
 */
@Component
public class TaskDispatchNode implements NodeAction {

    private static final Logger log = LoggerFactory.getLogger(TaskDispatchNode.class);

    private final ObjectMapper objectMapper;

    public TaskDispatchNode(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String subtasksJson = state.value(SqlAgentSpec.StateKey.SUBTASKS, "");
        int currentSubtask = readInt(state, SqlAgentSpec.StateKey.CURRENT_SUBTASK, 1);

        List<Map<String, Object>> subtasks = parseSubtasks(subtasksJson);
        int total = subtasks.size();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put(SqlAgentSpec.StateKey.CURRENT_SUBTASK, currentSubtask);

        if (total == 0 || currentSubtask > total) {
            log.info("[TaskDispatch] All {} subtasks dispatched — routing to SUMMARIZE.", total);
            out.put(SqlAgentSpec.StateKey.NEXT_NODE, SqlAgentSpec.Node.SUMMARIZE);
            return out;
        }

        Map<String, Object> current = subtasks.get(currentSubtask - 1);
        String instruction = current.get("instruction") instanceof String s ? s : "";

        PlanStep.ToolParameters params = new PlanStep.ToolParameters(instruction, null);
        PlanStep step = new PlanStep(1, "SQL_GENERATE_NODE", params);
        Plan syntheticPlan = new Plan(instruction, List.of(step));
        out.put(SqlAgentSpec.StateKey.PLAN, objectMapper.writeValueAsString(syntheticPlan));
        out.put(SqlAgentSpec.StateKey.CURRENT_STEP, 1);
        out.put(SqlAgentSpec.StateKey.FIX_ATTEMPT_COUNT, 0);
        out.put(SqlAgentSpec.StateKey.SQL_ERROR, "");

        out.put(SqlAgentSpec.StateKey.NEXT_NODE, SqlAgentSpec.Node.SQL_GENERATION);

        log.info("[TaskDispatch] subtask {}/{} → SQL_GENERATION, instruction='{}'",
                currentSubtask, total, instruction);

        return out;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseSubtasks(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            Object parsed = objectMapper.readValue(json, Object.class);
            if (parsed instanceof List) {
                List<Object> rawList = (List<Object>) parsed;
                java.util.List<Map<String, Object>> result = new java.util.ArrayList<>();
                for (Object item : rawList) {
                    if (item instanceof Map) {
                        result.add((Map<String, Object>) item);
                    }
                }
                return result;
            }
        } catch (Exception e) {
            log.debug("[TaskDispatch] JSON parse failed: {}", e.getMessage());
        }
        return List.of();
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
