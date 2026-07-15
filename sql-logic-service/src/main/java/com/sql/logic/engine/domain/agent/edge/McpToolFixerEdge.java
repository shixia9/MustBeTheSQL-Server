package com.sql.logic.engine.domain.agent.edge;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.action.EdgeAction;
import com.sql.logic.engine.domain.agent.AgentStateUtil;
import com.sql.logic.engine.domain.agent.SqlAgentSpec;
import org.springframework.stereotype.Component;

/**
 * MCP Tool Fixer Edge — routes failed MCP calls to the fixer for retry.
 * <p>
 * Reads {@code MCP_CALL_FAILED} and {@code MCP_FIX_ATTEMPT_COUNT}:
 * <ul>
 *   <li>{@code MCP_CALL_FAILED == true} <em>and</em> {@code MCP_FIX_ATTEMPT_COUNT < 2} →
 *       {@code MCP_TOOL_FIXER} (repair and retry)</li>
 *   <li>otherwise → {@code PLAN_DISPATCH} (success or retries exhausted)</li>
 * </ul>
 */
@Component
public class McpToolFixerEdge implements EdgeAction {

    private static final int MAX_FIX_ATTEMPTS = 2;

    @Override
    public String apply(OverAllState state) throws Exception {
        Object failedObj = state.value(SqlAgentSpec.StateKey.MCP_CALL_FAILED, Boolean.FALSE);
        boolean failed = failedObj instanceof Boolean ? (Boolean) failedObj
                : (failedObj != null && Boolean.parseBoolean(String.valueOf(failedObj)));

        if (failed) {
            int fixAttempt = readInt(state, SqlAgentSpec.StateKey.MCP_FIX_ATTEMPT_COUNT, 0);
            if (fixAttempt < MAX_FIX_ATTEMPTS) {
                return SqlAgentSpec.Node.MCP_TOOL_FIXER;
            }
        }
        // Success or retries exhausted → proceed to next step
        return SqlAgentSpec.Node.PLAN_DISPATCH;
    }

    private int readInt(OverAllState state, String key, int dflt) {
        Object v = state.value(key, (Integer) null);
        if (v == null) return dflt;
        if (v instanceof Number) return ((Number) v).intValue();
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            Long asLong = AgentStateUtil.toLong(v);
            return asLong == null ? dflt : asLong.intValue();
        }
    }

    public static AsyncEdgeAction async() {
        return AsyncEdgeAction.edge_async(new McpToolFixerEdge());
    }
}
