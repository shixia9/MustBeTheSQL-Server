package com.sql.logic.engine.domain.agent.edge;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.action.EdgeAction;
import com.sql.logic.engine.domain.agent.SqlAgentSpec;
import org.springframework.stereotype.Component;

/**
 * SQL Execution Edge (Phase 3) — the self-correction router.
 * <p>
 * Reads {@code SQL_ERROR} and {@code FIX_ATTEMPT_COUNT} (both written by
 * {@code SqlExecutionNode}):
 * <ul>
 *   <li>{@code SQL_ERROR} blank → success → {@code PLAN_DISPATCH} (node already
 *       advanced {@code CURRENT_STEP})</li>
 *   <li>{@code SQL_ERROR} present <em>and</em> {@code FIX_ATTEMPT_COUNT < 2} →
 *       {@code SQL_FIXER} (retry the same step)</li>
 *   <li>{@code SQL_ERROR} present <em>and</em> out of retries → the execution
 *       node cleared {@code SQL_ERROR} and embedded the failure into
 *       {@code SQL_EXECUTION_RESULT}, so this branch is unreachable; default to
 *       {@code PLAN_DISPATCH}</li>
 * </ul>
 */
@Component
public class SqlExecutionEdge implements EdgeAction {

    private static final int MAX_FIX_ATTEMPTS = 2;

    @Override
    public String apply(OverAllState state) throws Exception {
        String sqlError = state.value(SqlAgentSpec.StateKey.SQL_ERROR, "");
        if (sqlError != null && !sqlError.isBlank()) {
            int fixAttemptCount = readInt(state, SqlAgentSpec.StateKey.FIX_ATTEMPT_COUNT, 0);
            if (fixAttemptCount < MAX_FIX_ATTEMPTS) {
                return SqlAgentSpec.Node.SQL_FIXER;
            }
        }
        // Success or give-up (error already cleared) → advance the dispatcher.
        return SqlAgentSpec.Node.PLAN_DISPATCH;
    }

    private int readInt(OverAllState state, String key, int dflt) {
        Object v = state.value(key, (Integer) null);
        if (v == null) return dflt;
        if (v instanceof Number) return ((Number) v).intValue();
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            Long asLong = com.sql.logic.engine.domain.agent.AgentStateUtil.toLong(v);
            return asLong == null ? dflt : asLong.intValue();
        }
    }

    public static AsyncEdgeAction async() {
        return AsyncEdgeAction.edge_async(new SqlExecutionEdge());
    }
}