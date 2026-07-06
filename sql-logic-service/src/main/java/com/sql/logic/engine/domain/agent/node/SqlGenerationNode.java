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
 * SQL Generation Node — generates SQL for the current plan step.
 * <p>
 * Phase 3: gateless (feasibility is now a dedicated upstream node), and the
 * {@code execution_description} comes from the current {@code PlanStep.instruction}
 * (decoded from {@code PLAN} + {@code CURRENT_STEP}) rather than the raw rewrite
 * query. The schema context is the recalled {@code TABLE_RELATION} from Schema Linking.
 * <p>
 * Dialect is inferred from the database connection (MySQL / PostgreSQL) and injected
 * into the {@code new-sql-generate.st} prompt.
 */
@Component
public class SqlGenerationNode implements NodeAction {

    private static final Logger log = LoggerFactory.getLogger(SqlGenerationNode.class);

    private final LlmClientManager llmClientManager;
    private final PromptManager promptManager;
    private final DbConnectionConfDao dbConnectionConfDao;
    private final ObjectMapper objectMapper;
    private final LlmCallReporter llmCallReporter;

    public SqlGenerationNode(LlmClientManager llmClientManager,
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
        String rewriteQuery = state.value(SqlAgentSpec.StateKey.REWRITE_QUERY, "");
        if (rewriteQuery == null || rewriteQuery.isBlank()) {
            rewriteQuery = state.value(SqlAgentSpec.StateKey.INPUT, "");
        }
        String evidence = state.value(SqlAgentSpec.StateKey.EVIDENCE, "");
        String tableRelation = state.value(SqlAgentSpec.StateKey.TABLE_RELATION, "");

        Long connectionId = AgentStateUtil.toLong(state.value(SqlAgentSpec.StateKey.CONNECTION_ID, (Long) null));
        Long llmConfigId = AgentStateUtil.toLong(state.value(SqlAgentSpec.StateKey.LLM_CONFIG_ID, (Long) null));
        Long userId = AgentStateUtil.toLong(state.value(SqlAgentSpec.StateKey.USER_ID, (Long) null));
        int currentStep = readInt(state, SqlAgentSpec.StateKey.CURRENT_STEP, 1);

        // Determine dialect from database connection
        String dialect = "mysql"; // default
        if (connectionId != null) {
            DbConnectionConf conf = dbConnectionConfDao.selectById(connectionId);
            if (conf != null) {
                String dbType = conf.getDbType();
                if (dbType != null && dbType.toLowerCase().contains("postgres")) {
                    dialect = "postgresql";
                }
            }
        }

        // The actual task this SQL must fulfil = the current plan step's instruction.
        String executionDescription = currentInstruction(state, currentStep, rewriteQuery);

        // Schema context comes from Schema Linking (Phase 2). Nothing else needed here.
        String schemaInfo = (tableRelation != null && !tableRelation.isBlank())
                ? tableRelation
                : "（无可用 Schema，请仅基于问题描述谨慎生成 SQL）";

        String prompt = promptManager.render(SqlAgentSpec.PromptName.NEW_SQL_GENERATE, Map.of(
                "dialect", dialect,
                "question", rewriteQuery,
                "schema_info", schemaInfo,
                "evidence", evidence == null || evidence.isBlank() ? "无" : evidence,
                "execution_description", executionDescription,
                "execution_description_section", ""  // Description is the primary driver when no planner is wired
        ));

        LLMStrategy strategy = llmClientManager.resolveTraced(llmConfigId, userId,
                (TraceContext) state.value(SqlAgentSpec.StateKey.TRACE_CONTEXT).orElse(null),
                SqlAgentSpec.Node.SQL_GENERATION, llmCallReporter);
        String sql = strategy.generateSql(prompt, null);
        sql = MarkdownParserUtil.extractRawText(sql);

        log.info("[SqlGenerationNode] step={} generated SQL ({} chars)", currentStep, sql.length());

        return Map.of(SqlAgentSpec.StateKey.SQL_GENERATION_RESULT, sql);
    }

    /**
     * Resolve the instruction for the current plan step; fall back to the rewrite
     * query when no plan is available (e.g. degraded path).
     */
    private String currentInstruction(OverAllState state, int currentStep, String fallback) {
        String planJson = state.value(SqlAgentSpec.StateKey.PLAN, "");
        Plan plan = Plan.fromJson(objectMapper, planJson);
        if (plan == null || plan.executionPlan() == null) {
            return fallback;
        }
        List<PlanStep> steps = plan.executionPlan();
        if (currentStep < 1 || currentStep > steps.size()) {
            return fallback;
        }
        PlanStep step = steps.get(currentStep - 1);
        if (step.toolParameters() != null
                && step.toolParameters().instruction() != null
                && !step.toolParameters().instruction().isBlank()) {
            return step.toolParameters().instruction();
        }
        return fallback;
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