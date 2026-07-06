package com.sql.logic.engine.domain.agent.edge;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.action.EdgeAction;
import com.sql.logic.engine.domain.agent.SqlAgentSpec;
import org.springframework.stereotype.Component;

@Component
public class TaskDispatchEdge implements EdgeAction {

    @Override
    public String apply(OverAllState state) throws Exception {
        String next = state.value(SqlAgentSpec.StateKey.NEXT_NODE, "");
        if (next == null || next.isBlank()) {
            return SqlAgentSpec.Node.SUMMARIZE;
        }
        return next;
    }

    public static AsyncEdgeAction async() {
        return AsyncEdgeAction.edge_async(new TaskDispatchEdge());
    }
}
