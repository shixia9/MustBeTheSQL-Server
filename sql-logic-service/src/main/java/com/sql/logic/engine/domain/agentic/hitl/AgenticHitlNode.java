package com.sql.logic.engine.domain.agentic.hitl;

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
 * Pure control node for the agentic graph's human decision.
 *
 * <p>The graph is interrupted before this node.  The node is executed only on
 * resume, after the controller has written the decision and feedback into the
 * checkpoint state.</p>
 */
@Component
public class AgenticHitlNode implements NodeAction {

    private static final Logger log = LoggerFactory.getLogger(AgenticHitlNode.class);
    private static final int MAX_REJECTIONS = 3;

    @Override
    public Map<String, Object> apply(OverAllState state) {
        Boolean approved = readBoolean(state, SqlAgentSpec.StateKey.CONFIRMATION_APPROVED);
        int rejectionCount = readInt(state, SqlAgentSpec.StateKey.REPAIR_COUNT, 0);
        Map<String, Object> updates = new LinkedHashMap<>();

        if (approved == null) {
            log.warn("[AgenticHitlNode] resumed without a decision; terminating thread");
            updates.put(SqlAgentSpec.StateKey.HITL_STATUS, "FAILED");
            updates.put(SqlAgentSpec.StateKey.NEXT_NODE, StateGraph.END);
            return updates;
        }

        if (approved) {
            log.info("[AgenticHitlNode] plan approved; returning to Manager");
            updates.put(SqlAgentSpec.StateKey.HITL_STATUS, "APPROVED");
            updates.put(SqlAgentSpec.StateKey.NEXT_NODE, "MANAGER");
            updates.put(SqlAgentSpec.StateKey.HITL_GATE_EVALUATED, true);
            return updates;
        }

        int nextRejectionCount = rejectionCount + 1;
        updates.put(SqlAgentSpec.StateKey.REPAIR_COUNT, nextRejectionCount);
        updates.put(SqlAgentSpec.StateKey.HITL_GATE_EVALUATED, false);
        updates.put(SqlAgentSpec.StateKey.HITL_STATUS,
                nextRejectionCount >= MAX_REJECTIONS ? "REJECTED" : "REPLAN_REQUIRED");

        if (nextRejectionCount >= MAX_REJECTIONS) {
            log.warn("[AgenticHitlNode] rejection limit reached ({})", MAX_REJECTIONS);
            updates.put(SqlAgentSpec.StateKey.NEXT_NODE, StateGraph.END);
        } else {
            String feedback = state.value(SqlAgentSpec.StateKey.CONFIRMATION_FEEDBACK, "");
            updates.put(SqlAgentSpec.StateKey.CONFIRMATION_FEEDBACK,
                    feedback == null || feedback.isBlank() ? "用户拒绝了当前计划，请重新规划" : feedback);
            updates.put(SqlAgentSpec.StateKey.NEXT_NODE, "MANAGER");
        }
        return updates;
    }

    private Boolean readBoolean(OverAllState state, String key) {
        Object value = state.value(key, (Object) null);
        if (value == null) return null;
        if (value instanceof Boolean b) return b;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : Boolean.parseBoolean(text);
    }

    private int readInt(OverAllState state, String key, int fallback) {
        Object value = state.value(key, (Object) null);
        if (value instanceof Number n) return n.intValue();
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value).trim());
            } catch (NumberFormatException ignored) {
                // use fallback
            }
        }
        return fallback;
    }
}
