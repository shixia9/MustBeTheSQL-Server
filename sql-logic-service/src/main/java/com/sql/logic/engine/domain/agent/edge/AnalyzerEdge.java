package com.sql.logic.engine.domain.agent.edge;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.action.EdgeAction;
import com.sql.logic.engine.domain.agent.SqlAgentSpec;
import org.springframework.stereotype.Component;

/**
 * Analyzer Edge — routes on the {@code COMPLEXITY} verdict written by
 * {@code AnalyzerNode}.
 * <ul>
 *   <li>{@code SIMPLE} → {@code FEASIBILITY_ASSESSMENT} (enter the standard multi-phase workflow)</li>
 *   <li>{@code COMPLEX} → {@code TASK_SPLIT} (decompose into subtasks)</li>
 *   <li>{@code CLARIFY} → {@code REPORT} (ask the user for clarification)</li>
 * </ul>
 * Defaults to {@code FEASIBILITY_ASSESSMENT} when the verdict is missing.
 */
@Component
public class AnalyzerEdge implements EdgeAction {

    @Override
    public String apply(OverAllState state) throws Exception {
        String complexity = state.value(SqlAgentSpec.StateKey.COMPLEXITY, "SIMPLE");
        if (complexity == null || complexity.isBlank()) {
            return SqlAgentSpec.Node.FEASIBILITY_ASSESSMENT;
        }
        switch (complexity.trim().toUpperCase()) {
            case "SIMPLE":
                return SqlAgentSpec.Node.FEASIBILITY_ASSESSMENT;
            case "COMPLEX":
                return SqlAgentSpec.Node.TASK_SPLIT;
            case "CLARIFY":
                return SqlAgentSpec.Node.REPORT;
            default:
                return SqlAgentSpec.Node.FEASIBILITY_ASSESSMENT;
        }
    }

    public static AsyncEdgeAction async() {
        return AsyncEdgeAction.edge_async(new AnalyzerEdge());
    }
}
