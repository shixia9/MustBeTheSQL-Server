package com.sql.logic.engine.domain.agent.edge;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.action.EdgeAction;
import com.sql.logic.engine.domain.agent.SqlAgentSpec;
import com.sql.logic.engine.domain.agent.node.FeasibilityAssessmentNode;
import org.springframework.stereotype.Component;

/**
 * Feasibility Assessment Edge (Phase 3).
 * <p>
 * Routes on the plain-text verdict written by {@code FeasibilityAssessmentNode}:
 * <ul>
 *   <li>{@code 《数据分析》} → {@code PLANNER} (proceed to multi-step planning)</li>
 *   <li>anything else (澄清 / 闲聊) → {@code REPORT} (the gate already pre-filled
 *       {@code REPORT_RESULT}; {@code ReportNode} short-circuits)</li>
 * </ul>
 * The mapping from returned strings to target nodes is defined in the graph config.
 */
@Component
public class FeasibilityAssessmentEdge implements EdgeAction {

    @Override
    public String apply(OverAllState state) throws Exception {
        String verdict = state.value(SqlAgentSpec.StateKey.FEASIBILITY_RESULT, "");
        if (verdict != null && verdict.contains(FeasibilityAssessmentNode.TAG_ANALYSIS)) {
            return SqlAgentSpec.Node.PLANNER;
        }
        return SqlAgentSpec.Node.REPORT;
    }

    /** Wrap this edge for the {@code StateGraph.addConditionalEdges} API. */
    public static AsyncEdgeAction async() {
        return AsyncEdgeAction.edge_async(new FeasibilityAssessmentEdge());
    }
}