package com.sql.logic.engine.domain.agent.edge;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.action.EdgeAction;
import com.sql.logic.engine.domain.agent.SqlAgentSpec;
import org.springframework.stereotype.Component;

/**
 * HITL Edge (Phase 4) — routes after the {@code HitlNode} based on its computed
 * {@code NEXT_NODE}. The {@code HitlNode} is a pure control node (no LLM) that reads
 * the human decision injected via {@code updateState} on resume:
 * <ul>
 *   <li>{@code PLAN_DISPATCH} — approved, proceed to execute the plan.</li>
 *   <li>{@code PLANNER} — rejected, push back to re-plan with the user's feedback.</li>
 *   <li>{@code END} — repair count exhausted (≥3 rejections) or no decision yet.</li>
 * </ul>
 * Defaults to {@code END} on a null/empty value.
 */
@Component
public class HitlEdge implements EdgeAction {

    @Override
    public String apply(OverAllState state) throws Exception {
        String next = state.value(SqlAgentSpec.StateKey.NEXT_NODE, StateGraph.END);
        if (next == null || next.isBlank()) {
            return StateGraph.END;
        }
        return next;
    }

    public static AsyncEdgeAction async() {
        return AsyncEdgeAction.edge_async(new HitlEdge());
    }
}