package com.sql.logic.engine.domain.agent.edge;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.action.EdgeAction;
import com.sql.logic.engine.domain.agent.SqlAgentSpec;
import org.springframework.stereotype.Component;

/**
 * HITL Gate Edge (Phase 4).
 * <p>
 * Routes after the {@code HitlGateNode} based on its written {@code NEEDS_HUMAN_REVIEW}
 * flag: {@code true} → {@code HITL} (interrupt so the user can approve), {@code false}
 * → {@code PLAN_DISPATCH} (proceed straight to execution). Defaults to PLAN_DISPATCH
 * when the flag is absent/malformed, so a gate failure never traps the graph.
 */
@Component
public class HitlGateEdge implements EdgeAction {

    @Override
    public String apply(OverAllState state) throws Exception {
        Object v = state.value(SqlAgentSpec.StateKey.NEEDS_HUMAN_REVIEW, Boolean.FALSE);
        boolean needsReview = false;
        if (v instanceof Boolean) {
            needsReview = (Boolean) v;
        } else if (v != null) {
            needsReview = Boolean.parseBoolean(String.valueOf(v).trim());
        }
        return needsReview ? SqlAgentSpec.Node.HITL : SqlAgentSpec.Node.PLAN_DISPATCH;
    }

    public static AsyncEdgeAction async() {
        return AsyncEdgeAction.edge_async(new HitlGateEdge());
    }
}