package com.sql.logic.engine.domain.agent.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.sql.logic.engine.domain.agent.SqlAgentSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HITL Node (Phase 4) — pure control node, no LLM call.
 * <p>
 * Acts as the gate the graph interrupts before (via {@code interruptBefore(HITL)} in
 * the compile config). On resume, the controller injects {@code CONFIRMATION_APPROVED}
 * (and optionally {@code CONFIRMATION_FEEDBACK}) through {@code CompiledGraph.updateState}.
 * This node then decides the next target by writing {@code NEXT_NODE}:
 * <ul>
 *   <li>{@code REPAIR_COUNT >= 3} → {@code END} (give up after 3 rejected re-plans).</li>
 *   <li>{@code approved=true}   → {@code PLAN_DISPATCH} (execute the plan).</li>
 *   <li>{@code approved=false} → {@code PLANNER} (re-plan with the feedback,
 *       bump {@code REPAIR_COUNT}).</li>
 * </ul>
 * When the graph first pauses here (before the human decides), {@code CONFIRMATION_APPROVED}
 * is still null — in that case this method returns {@code NEXT_NODE=END} as a sentinel,
 * but the engine halts before executing the node body anyway, so this value is only
 * meaningful on the resume path.
 */
@Component
public class HitlNode implements NodeAction {

    private static final Logger log = LoggerFactory.getLogger(HitlNode.class);
    private static final int MAX_REPAIR_COUNT = 3;

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        int repairCount = readInt(state, SqlAgentSpec.StateKey.REPAIR_COUNT, 1);

        Map<String, Object> out = new LinkedHashMap<>();

        if (repairCount >= MAX_REPAIR_COUNT) {
            log.warn("[HitlNode] Repair count {} >= {} — giving up, routing to END.", repairCount, MAX_REPAIR_COUNT);
            out.put(SqlAgentSpec.StateKey.NEXT_NODE, StateGraph.END);
            return out;
        }

        Boolean approved = readBool(state, SqlAgentSpec.StateKey.CONFIRMATION_APPROVED);

        if (approved == null) {
            // No decision yet — should not normally happen on resume, but guard anyway.
            log.warn("[HitlNode] No confirmation decision present — routing to END.");
            out.put(SqlAgentSpec.StateKey.NEXT_NODE, StateGraph.END);
            return out;
        }

        if (approved) {
            log.info("[HitlNode] Plan approved — routing to PLAN_DISPATCH.");
            out.put(SqlAgentSpec.StateKey.NEXT_NODE, SqlAgentSpec.Node.PLAN_DISPATCH);
        } else {
            String feedback = state.value(SqlAgentSpec.StateKey.CONFIRMATION_FEEDBACK, "");
            if (feedback == null || feedback.isBlank()) {
                feedback = "Plan rejected by user";
            }
            log.info("[HitlNode] Plan rejected (repairCount {}) — routing back to PLANNER with feedback.", repairCount);
            out.put(SqlAgentSpec.StateKey.NEXT_NODE, SqlAgentSpec.Node.PLANNER);
            out.put(SqlAgentSpec.StateKey.CONFIRMATION_FEEDBACK, feedback);
            out.put(SqlAgentSpec.StateKey.REPAIR_COUNT, repairCount + 1);
        }
        return out;
    }

    private int readInt(OverAllState state, String key, int dflt) {
        Object v = state.value(key, (Integer) null);
        if (v == null) return dflt;
        if (v instanceof Number) return ((Number) v).intValue();
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    private Boolean readBool(OverAllState state, String key) {
        Object v = state.value(key, (Boolean) null);
        if (v == null) return null;
        if (v instanceof Boolean) return (Boolean) v;
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return null;
        return Boolean.parseBoolean(s);
    }
}