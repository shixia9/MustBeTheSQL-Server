package com.sql.logic.engine.domain.agent.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sql.logic.engine.domain.agent.AgentStateUtil;
import com.sql.logic.engine.domain.agent.SqlAgentSpec;
import com.sql.logic.engine.domain.agent.model.SqlExecutionResult;
import com.sql.logic.engine.domain.agent.service.SqlExecutionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SQL Execution Node (Phase 3) — actually runs the generated SQL on the user's
 * selected database connection via {@link SqlExecutionService}.
 * <p>
 * Success path: writes {@code SQL_EXECUTION_RESULT} (JSON), clears {@code SQL_ERROR},
 * and advances {@code CURRENT_STEP}. The conditional {@code SqlExecutionEdge} then
 * loops back to {@code PLAN_DISPATCH} for the next step.
 * <p>
 * Failure path: if retries remain ({@code FIX_ATTEMPT_COUNT < 2}), writes {@code SQL_ERROR}
 * and leaves {@code CURRENT_STEP} untouched — the edge routes to {@code SQL_FIXER},
 * which loops back here for a retry. If no retries remain, the step is treated as
 * permanently failed: the error message is embedded in {@code SQL_EXECUTION_RESULT},
 * {@code SQL_ERROR} is cleared (so the edge advances past the step), and
 * {@code CURRENT_STEP} is incremented to avoid a stuck loop.
 */
@Component
public class SqlExecutionNode implements NodeAction {

    private static final Logger log = LoggerFactory.getLogger(SqlExecutionNode.class);
    private static final int MAX_FIX_ATTEMPTS = 2;

    private final SqlExecutionService sqlExecutionService;
    private final ObjectMapper objectMapper;

    public SqlExecutionNode(SqlExecutionService sqlExecutionService, ObjectMapper objectMapper) {
        this.sqlExecutionService = sqlExecutionService;
        this.objectMapper = objectMapper;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String sql = state.value(SqlAgentSpec.StateKey.SQL_GENERATION_RESULT, "");
        Long connectionId = AgentStateUtil.toLong(state.value(SqlAgentSpec.StateKey.CONNECTION_ID, (Long) null));
        Long userId = AgentStateUtil.toLong(state.value(SqlAgentSpec.StateKey.USER_ID, (Long) null));
        String schemaName = state.value(SqlAgentSpec.StateKey.SCHEMA_NAME, "");
        int currentStep = readInt(state, SqlAgentSpec.StateKey.CURRENT_STEP, 1);
        int fixAttemptCount = readInt(state, SqlAgentSpec.StateKey.FIX_ATTEMPT_COUNT, 0);

        Map<String, Object> out = new LinkedHashMap<>();

        if (sql == null || sql.isBlank()) {
            log.warn("[SqlExecutionNode] No SQL to execute at step {}.", currentStep);
            // Nothing to run — embed a note and advance.
            out.put(SqlAgentSpec.StateKey.SQL_EXECUTION_RESULT, "{}");
            out.put(SqlAgentSpec.StateKey.SQL_ERROR, "");
            out.put(SqlAgentSpec.StateKey.CURRENT_STEP, currentStep + 1);
            return out;
        }

        SqlExecutionResult result = sqlExecutionService.execute(userId, connectionId, sql, schemaName);

        if (result.hasError()) {
            log.info("[SqlExecutionNode] SQL failed (step={}, fixAttempt={}) — {}",
                    currentStep, fixAttemptCount, result.getErrorMsg());

            if (fixAttemptCount < MAX_FIX_ATTEMPTS) {
                // Hand the error to the fixer; keep the cursor on this step.
                out.put(SqlAgentSpec.StateKey.SQL_ERROR, result.getErrorMsg());
                out.put(SqlAgentSpec.StateKey.SQL_EXECUTION_RESULT, "");
            } else {
                // Out of retries — accept the failure, embed the error in the result,
                // clear SQL_ERROR so the edge advances, and move on.
                log.warn("[SqlExecutionNode] Giving up on step {} after {} fix attempts.", currentStep, fixAttemptCount);
                String failureJson = objectMapper.writeValueAsString(Map.of(
                        "errorMsg", result.getErrorMsg(),
                        "failedSql", sql,
                        "skipped", true
                ));
                out.put(SqlAgentSpec.StateKey.SQL_EXECUTION_RESULT, failureJson);
                out.put(SqlAgentSpec.StateKey.SQL_ERROR, "");  // clear so edge advances
                out.put(SqlAgentSpec.StateKey.CURRENT_STEP, currentStep + 1);
            }
            return out;
        }

        // Success — serialize columns/rows and advance the cursor.
        String resultJson = objectMapper.writeValueAsString(Map.of(
                "columns", result.getColumns() == null ? List.of() : result.getColumns(),
                "rows", result.getRows() == null ? List.of() : result.getRows(),
                "rowCount", result.getRowCount(),
                "sql", sql
        ));
        log.info("[SqlExecutionNode] OK step={} rows={}", currentStep, result.getRowCount());
        out.put(SqlAgentSpec.StateKey.SQL_EXECUTION_RESULT, resultJson);
        out.put(SqlAgentSpec.StateKey.SQL_ERROR, "");
        out.put(SqlAgentSpec.StateKey.CURRENT_STEP, currentStep + 1);
        return out;
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
}