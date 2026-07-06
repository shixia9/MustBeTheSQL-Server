package com.sql.logic.engine.domain.agent.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sql.logic.engine.domain.agent.AgentStateUtil;
import com.sql.logic.engine.domain.agent.SqlAgentSpec;
import com.sql.logic.engine.domain.agent.dto.Plan;
import com.sql.logic.engine.domain.agent.dto.PlanStep;
import com.sql.logic.engine.domain.agent.prompt.PromptManager;
import com.sql.logic.engine.domain.agent.core.LlmClientManager;
import com.sql.logic.engine.domain.agent.ha.LlmCallReporter;
import com.sql.logic.engine.domain.agent.strategy.LLMStrategy;
import com.sql.logic.engine.domain.trace.TraceContext;
import com.sql.logic.engine.infrastructure.dao.DbConnectionConfDao;
import com.sql.logic.engine.infrastructure.po.DbConnectionConf;
import com.sql.logic.engine.infrastructure.util.MarkdownParserUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * SQL Fixer Node (Phase 3) — repairs a failed SQL statement using the DB error.
 * <p>
 * Reads {@code SQL_ERROR} + {@code SQL_GENERATION_RESULT} (the failed SQL) + the
 * current plan step's {@code instruction}, renders {@code sql-error-fixer.st}, and
 * overwrites {@code SQL_GENERATION_RESULT} with the repaired SQL. Clears
 * {@code SQL_ERROR} and bumps {@code FIX_ATTEMPT_COUNT}. The fixed edge loops back to
 * {@code SQL_EXECUTION} for a retry.
 * <p>
 * The Step 2 self-correction loop is capped at 2 attempts; the give-up decision is
 * owned by {@code SqlExecutionNode} (not this node).
 */
@Component
public class SqlFixerNode implements NodeAction {

    private static final Logger log = LoggerFactory.getLogger(SqlFixerNode.class);

    private final LlmClientManager llmClientManager;
    private final PromptManager promptManager;
    private final DbConnectionConfDao dbConnectionConfDao;
    private final ObjectMapper objectMapper;
    private final LlmCallReporter llmCallReporter;

    public SqlFixerNode(LlmClientManager llmClientManager,
                         PromptManager promptManager,
                         DbConnectionConfDao dbConnectionConfDao,
                         ObjectMapper objectMapper,
                         LlmCallReporter llmCallReporter) {
        this.llmClientManager = llmClientManager;
        this.promptManager = promptManager;
        this.dbConnectionConfDao = dbConnectionConfDao;
        this.objectMapper = objectMapper;
        this.llmCallReporter = llmCallReporter;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String errorMsg = state.value(SqlAgentSpec.StateKey.SQL_ERROR, "");
        String failedSql = state.value(SqlAgentSpec.StateKey.SQL_GENERATION_RESULT, "");
        String tableRelation = state.value(SqlAgentSpec.StateKey.TABLE_RELATION, "");
        String evidence = state.value(SqlAgentSpec.StateKey.EVIDENCE, "");
        String rewriteQuery = state.value(SqlAgentSpec.StateKey.REWRITE_QUERY, "");
        if (rewriteQuery == null || rewriteQuery.isBlank()) {
            rewriteQuery = state.value(SqlAgentSpec.StateKey.INPUT, "");
        }

        Long connectionId = AgentStateUtil.toLong(state.value(SqlAgentSpec.StateKey.CONNECTION_ID, (Long) null));
        Long llmConfigId = AgentStateUtil.toLong(state.value(SqlAgentSpec.StateKey.LLM_CONFIG_ID, (Long) null));
        Long userId = AgentStateUtil.toLong(state.value(SqlAgentSpec.StateKey.USER_ID, (Long) null));
        int currentStep = readInt(state, SqlAgentSpec.StateKey.CURRENT_STEP, 1);
        int fixAttemptCount = readInt(state, SqlAgentSpec.StateKey.FIX_ATTEMPT_COUNT, 0);

        String dialect = inferDialect(connectionId);
        String instruction = currentInstruction(state, currentStep);
        String schemaInfo = (tableRelation == null || tableRelation.isBlank()) ? "（无可用 Schema）" : tableRelation;
        String evidenceText = (evidence == null || evidence.isBlank()) ? "无" : evidence;

        String prompt = promptManager.render(SqlAgentSpec.PromptName.SQL_ERROR_FIXER, Map.of(
                "dialect", dialect,
                "error_message", errorMsg == null ? "" : errorMsg,
                "schema_info", schemaInfo,
                "execution_description", instruction,
                "error_sql", failedSql == null ? "" : failedSql,
                "question", rewriteQuery,
                "evidence", evidenceText
        ));

        LLMStrategy strategy = llmClientManager.resolveTraced(llmConfigId, userId,
                (TraceContext) state.value(SqlAgentSpec.StateKey.TRACE_CONTEXT).orElse(null),
                SqlAgentSpec.Node.SQL_FIXER, llmCallReporter);
        String fixed = strategy.generateSql(prompt, null);
        fixed = MarkdownParserUtil.extractRawText(fixed);
        int failedLen = failedSql == null ? 0 : failedSql.length();

        log.info("[SqlFixerNode] fix attempt {} step={} ({} chars → {} chars)",
                fixAttemptCount + 1, currentStep, failedLen, fixed.length());

        return Map.of(
                SqlAgentSpec.StateKey.SQL_GENERATION_RESULT, fixed,
                SqlAgentSpec.StateKey.SQL_ERROR, "", // clear so SqlExecutionNode retries clean
                SqlAgentSpec.StateKey.FIX_ATTEMPT_COUNT, fixAttemptCount + 1
        );
    }

    /** Resolve the current step's instruction text, or fall back to the rewrite query. */
    private String currentInstruction(OverAllState state, int currentStep) {
        String planJson = state.value(SqlAgentSpec.StateKey.PLAN, "");
        Plan plan = Plan.fromJson(objectMapper, planJson);
        if (plan == null || plan.executionPlan() == null) {
            return state.value(SqlAgentSpec.StateKey.REWRITE_QUERY, "");
        }
        List<PlanStep> steps = plan.executionPlan();
        if (currentStep < 1 || currentStep > steps.size()) {
            return state.value(SqlAgentSpec.StateKey.REWRITE_QUERY, "");
        }
        PlanStep step = steps.get(currentStep - 1);
        if (step.toolParameters() != null
                && step.toolParameters().instruction() != null
                && !step.toolParameters().instruction().isBlank()) {
            return step.toolParameters().instruction();
        }
        return state.value(SqlAgentSpec.StateKey.REWRITE_QUERY, "");
    }

    /** Mirror SqlGenerationNode's dialect inference. */
    private String inferDialect(Long connectionId) {
        if (connectionId == null) return "mysql";
        DbConnectionConf conf = dbConnectionConfDao.selectById(connectionId);
        if (conf != null && conf.getDbType() != null
                && conf.getDbType().toLowerCase().contains("postgres")) {
            return "postgresql";
        }
        return "mysql";
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