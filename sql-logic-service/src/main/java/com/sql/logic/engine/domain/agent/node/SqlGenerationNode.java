package com.sql.logic.engine.domain.agent.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.sql.logic.engine.domain.agent.SqlAgentSpec;
import com.sql.logic.engine.domain.agent.prompt.PromptManager;
import com.sql.logic.engine.domain.agent.core.LlmClientManager;
import com.sql.logic.engine.domain.agent.strategy.LLMStrategy;
import com.sql.logic.engine.application.service.DatabaseMetaDataService;
import com.sql.logic.engine.infrastructure.dao.DbConnectionConfDao;
import com.sql.logic.engine.infrastructure.po.DbConnectionConf;
import com.sql.logic.engine.infrastructure.util.MarkdownParserUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SQL Generation Node — generates SQL from the rewritten query + schema context.
 * <p>
 * Uses the same DDL retrieval mechanism as SqlAiAgentImpl for backward compatibility,
 * but structures the prompt using the enhanced PromptManager template.
 * The schema context is built from DatabaseMetaDataService's cached DDL fetching.
 * <p>
 * Includes a lightweight feasibility gate: if the rewritten query is identified as
 * non-queryable (greetings, small talk, etc.), the node returns a clarification
 * message instead of attempting SQL generation, saving an unnecessary LLM call.
 */
@Component
public class SqlGenerationNode implements NodeAction {

    private static final Logger log = LoggerFactory.getLogger(SqlGenerationNode.class);

    /**
     * Keywords/patterns that indicate the user's input is not a data query.
     * If the rewritten query matches any of these, we skip SQL generation.
     */
    private static final Set<String> NON_QUERY_INDICATORS = Set.of(
            "无具体查询内容", "无有效查询内容", "查询相关数据或信息", "闲聊", "不涉及数据",
            "no specific query", "not a data query", "general conversation"
    );

    private final LlmClientManager llmClientManager;
    private final PromptManager promptManager;
    private final DatabaseMetaDataService databaseMetaDataService;
    private final DbConnectionConfDao dbConnectionConfDao;

    public SqlGenerationNode(LlmClientManager llmClientManager,
                             PromptManager promptManager,
                             DatabaseMetaDataService databaseMetaDataService,
                             DbConnectionConfDao dbConnectionConfDao) {
        this.llmClientManager = llmClientManager;
        this.promptManager = promptManager;
        this.databaseMetaDataService = databaseMetaDataService;
        this.dbConnectionConfDao = dbConnectionConfDao;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String rewriteQuery = state.value(SqlAgentSpec.StateKey.REWRITE_QUERY, "");
        String evidence = state.value(SqlAgentSpec.StateKey.EVIDENCE, "");
        String existingTableRelation = state.value(SqlAgentSpec.StateKey.TABLE_RELATION, "");

        Object connectionIdObj = state.value(SqlAgentSpec.StateKey.CONNECTION_ID, null);
        Long connectionId = connectionIdObj instanceof Long ? (Long) connectionIdObj : null;
        Object llmConfigIdObj = state.value(SqlAgentSpec.StateKey.LLM_CONFIG_ID, null);
        Long llmConfigId = llmConfigIdObj instanceof Long ? (Long) llmConfigIdObj : null;
        Object userIdObj = state.value(SqlAgentSpec.StateKey.USER_ID, null);
        Long userId = userIdObj instanceof Long ? (Long) userIdObj : null;

        // If no rewritten query, fall back to original input
        if (rewriteQuery == null || rewriteQuery.isBlank()) {
            rewriteQuery = state.value(SqlAgentSpec.StateKey.INPUT, "");
        }

        // Lightweight feasibility gate: skip SQL generation for non-query inputs
        if (isNonQueryable(rewriteQuery)) {
            log.info("[SqlGenerationNode] Non-queryable input detected, skipping SQL generation: '{}'", rewriteQuery);
            return Map.of(SqlAgentSpec.StateKey.SQL_GENERATION_RESULT,
                    "您的输入似乎不是一个数据查询请求。请尝试描述您想查询的数据，例如：\"查询所有客户\"" +
                    "或\"展示销售额前10的产品\"。");
        }

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

        // Build schema context
        String schemaInfo = buildSchemaContext(connectionId, existingTableRelation, state);

        // Build execution description (for Phase 1, same as query since no planner yet)
        String executionDescription = rewriteQuery;

        // Render the SQL generation prompt
        String prompt = promptManager.render(SqlAgentSpec.PromptName.NEW_SQL_GENERATE, Map.of(
                "dialect", dialect,
                "question", rewriteQuery,
                "schema_info", schemaInfo,
                "evidence", evidence == null || evidence.isBlank() ? "无" : evidence,
                "execution_description", executionDescription,
                "execution_description_section", ""  // Phase 1: no planner step description
        ));

        // Generate SQL using the resolved LLM strategy (user default → system default)
        LLMStrategy strategy = llmClientManager.resolveStrategy(llmConfigId, userId);
        String sql = strategy.generateSql(prompt, null);

        // Strip markdown code fences
        sql = MarkdownParserUtil.extractRawText(sql);

        log.info("[SqlGenerationNode] Generated SQL: {}", sql);

        return Map.of(SqlAgentSpec.StateKey.SQL_GENERATION_RESULT, sql);
    }

    /**
     * Lightweight feasibility check: determine if the rewritten query is a data query
     * or a non-queryable input (greetings, small talk, etc.).
     * <p>
     * This avoids wasting an LLM call on inputs that cannot produce meaningful SQL.
     * In Phase 3, this will be replaced by a dedicated FEASIBILITY_ASSESSMENT node
     * with conditional graph routing.
     */
    private boolean isNonQueryable(String rewriteQuery) {
        if (rewriteQuery == null || rewriteQuery.isBlank()) {
            return true;
        }
        String trimmed = rewriteQuery.trim().toLowerCase();
        // Check against known non-query indicators from EvidenceRecallNode
        for (String indicator : NON_QUERY_INDICATORS) {
            if (trimmed.contains(indicator.toLowerCase())) {
                return true;
            }
        }
        // Very short queries (< 3 chars after trimming) that aren't likely SQL-related
        if (trimmed.length() < 3 && !trimmed.matches(".*[a-z0-9_]+.*")) {
            return true;
        }
        return false;
    }

    /**
     * Build schema context for the prompt.
     * If tableRelation is provided (from Schema Linking), use it.
     * Otherwise, fall back to full DDL from selected tables.
     */
    private String buildSchemaContext(Long connectionId, String tableRelation, OverAllState state) {
        // Phase 2: if Schema Linking has already populated tableRelation, use it
        if (tableRelation != null && !tableRelation.isBlank()) {
            return tableRelation;
        }

        // Phase 1 fallback: build DDL context from selected tables or all tables
        if (connectionId == null) {
            return "（未选择数据库连接，无法提供 Schema 信息）";
        }

        try {
            // Try to get table names from state
            List<String> tableNames = null;
            Object tableNamesObj = state.value(SqlAgentSpec.StateKey.TABLE_NAMES, null);
            if (tableNamesObj instanceof List<?>) {
                @SuppressWarnings("unchecked")
                List<String> casted = (List<String>) tableNamesObj;
                tableNames = casted;
            }

            if (tableNames != null && !tableNames.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (String tableName : tableNames) {
                    String ddl = databaseMetaDataService.getTableDDL(connectionId, tableName);
                    if (ddl != null) {
                        sb.append(ddl).append("\n\n");
                    }
                }
                return sb.toString();
            }

            // No tables specified — list all tables with brief DDL
            List<String> allTables = databaseMetaDataService.getTableNames(connectionId);
            if (allTables == null || allTables.isEmpty()) {
                return "（数据库无可用表）";
            }

            StringBuilder sb = new StringBuilder();
            for (String tableName : allTables) {
                String ddl = databaseMetaDataService.getTableDDL(connectionId, tableName);
                if (ddl != null) {
                    sb.append(ddl).append("\n\n");
                }
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("[SqlGenerationNode] Failed to build schema context", e);
            return "（Schema 信息加载失败）";
        }
    }
}