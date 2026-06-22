package com.sql.logic.engine.domain.agent.edge;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.action.EdgeAction;
import com.sql.logic.engine.domain.agent.SqlAgentSpec;
import org.springframework.stereotype.Component;

/**
 * Plan Dispatch Edge (Phase 3).
 * <p>
 * Pure pass-through: {@code PlanDispatchNode} computed {@code NEXT_NODE} (an actual
 * graph node id or {@code StateGraph.END}). This edge just returns it, defaulting
 * to {@code END} on a null/empty value. The mapping from returned strings to target
 * nodes is defined in the graph config (must cover SQL_GENERATION, REPORT, END).
 */
@Component
public class PlanDispatchEdge implements EdgeAction {

    @Override
    public String apply(OverAllState state) throws Exception {
        String next = state.value(SqlAgentSpec.StateKey.NEXT_NODE, com.alibaba.cloud.ai.graph.StateGraph.END);
        if (next == null || next.isBlank()) {
            return com.alibaba.cloud.ai.graph.StateGraph.END;
        }
        return next;
    }

    public static AsyncEdgeAction async() {
        return AsyncEdgeAction.edge_async(new PlanDispatchEdge());
    }
}