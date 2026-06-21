package com.sql.logic.engine.domain.agent.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sql.logic.engine.application.service.ColumnSampleService;
import com.sql.logic.engine.application.service.DatabaseMetaDataService;
import com.sql.logic.engine.application.service.SchemaRelationService;
import com.sql.logic.engine.domain.agent.AgentStateUtil;
import com.sql.logic.engine.domain.agent.SqlAgentSpec;
import com.sql.logic.engine.domain.agent.dto.ForeignKeyRelation;
import com.sql.logic.engine.domain.agent.prompt.PromptManager;
import com.sql.logic.engine.domain.agent.core.LlmClientManager;
import com.sql.logic.engine.domain.agent.strategy.LLMStrategy;
import com.sql.logic.engine.infrastructure.util.MarkdownParserUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Schema Linking Node — enriches the schema context for SQL generation.
 * <p>
 * This is the core node of Phase 2. It takes the rewritten query from
 * EvidenceRecallNode, retrieves the database schema (DDL + FK relations +
 * data samples), and uses an LLM to filter the schema to only the tables
 * relevant to the user's question.
 * <p>
 * Pipeline:
 * 1. Collect table names (from state TABLE_NAMES or all tables in the database)
 * 2. Expand table set with FK-connected tables
 * 3. Build full schema context (DDL + FK expressions + data samples)
 * 4. Use LLM with mix-selector prompt to filter relevant tables
 * 5. Rebuild filtered schema context for only the selected tables
 * 6. Output filtered schema as TABLE_RELATION state key
 * <p>
 * In Phase 2 (no vector DB), all tables are sent to the LLM for filtering.
 * For large schemas (>20 tables), a condensed format is used for the initial
 * filter pass to stay within token limits.
 */
@Component
public class SchemaLinkingNode implements NodeAction {

    private static final Logger log = LoggerFactory.getLogger(SchemaLinkingNode.class);

    /** Maximum number of tables before switching to condensed format for initial LLM filter. */
    private static final int LARGE_SCHEMA_THRESHOLD = 20;

    private final SchemaRelationService schemaRelationService;
    private final ColumnSampleService columnSampleService;
    private final DatabaseMetaDataService databaseMetaDataService;
    private final LlmClientManager llmClientManager;
    private final PromptManager promptManager;
    private final ObjectMapper objectMapper;

    public SchemaLinkingNode(SchemaRelationService schemaRelationService,
                             ColumnSampleService columnSampleService,
                             DatabaseMetaDataService databaseMetaDataService,
                             LlmClientManager llmClientManager,
                             PromptManager promptManager,
                             ObjectMapper objectMapper) {
        this.schemaRelationService = schemaRelationService;
        this.columnSampleService = columnSampleService;
        this.databaseMetaDataService = databaseMetaDataService;
        this.llmClientManager = llmClientManager;
        this.promptManager = promptManager;
        this.objectMapper = objectMapper;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        // 1. Read state
        String rewriteQuery = state.value(SqlAgentSpec.StateKey.REWRITE_QUERY, "");
        String evidence = state.value(SqlAgentSpec.StateKey.EVIDENCE, "");
        Long connectionId = AgentStateUtil.toLong(
                state.value(SqlAgentSpec.StateKey.CONNECTION_ID, null));
        Long llmConfigId = AgentStateUtil.toLong(
                state.value(SqlAgentSpec.StateKey.LLM_CONFIG_ID, null));
        Long userId = AgentStateUtil.toLong(
                state.value(SqlAgentSpec.StateKey.USER_ID, null));

        // If no rewritten query, fall back to original input
        if (rewriteQuery == null || rewriteQuery.isBlank()) {
            rewriteQuery = state.value(SqlAgentSpec.StateKey.INPUT, "");
        }

        if (connectionId == null) {
            log.warn("[SchemaLinkingNode] No connectionId in state, skipping schema linking");
            return Map.of(SqlAgentSpec.StateKey.TABLE_RELATION, "");
        }

        // 2. Collect initial table names (from state or all tables)
        List<String> tableNames = getInitialTableNames(state, connectionId);
        if (tableNames.isEmpty()) {
            log.warn("[SchemaLinkingNode] No tables found for connectionId={}", connectionId);
            return Map.of(SqlAgentSpec.StateKey.TABLE_RELATION, "");
        }

        log.info("[SchemaLinkingNode] Starting schema linking for query='{}', tables count={}", rewriteQuery, tableNames.size());

        // 3. Expand table set with FK-connected tables
        Set<String> expandedTables = schemaRelationService.expandWithJoinTables(
                connectionId, new LinkedHashSet<>(tableNames));
        List<String> expandedTableList = new ArrayList<>(expandedTables);
        log.info("[SchemaLinkingNode] Expanded tables: {} -> {} tables", tableNames.size(), expandedTableList.size());

        // 4. Get FK relations for all expanded tables
        List<ForeignKeyRelation> allRelations = schemaRelationService.getForeignKeyRelations(connectionId);
        List<ForeignKeyRelation> relevantRelations = schemaRelationService.filterRelationsForTables(
                allRelations, expandedTables);

        // 5. Build schema context for LLM filtering
        boolean isLargeSchema = expandedTableList.size() > LARGE_SCHEMA_THRESHOLD;
        String schemaContext;
        if (isLargeSchema) {
            // Condensed format: table names + column names only, no full DDL or samples
            schemaContext = buildCondensedSchemaContext(connectionId, expandedTableList, relevantRelations);
        } else {
            // Full format: DDL + FK expressions + data samples
            schemaContext = buildFullSchemaContext(connectionId, expandedTableList, relevantRelations);
        }

        // 6. Render mix-selector prompt and call LLM for table filtering
        String prompt = promptManager.render(SqlAgentSpec.PromptName.MIX_SELECTOR, Map.of(
                "schema_info", schemaContext,
                "question", rewriteQuery,
                "evidence", evidence == null || evidence.isBlank() ? "无" : evidence
        ));

        LLMStrategy strategy = llmClientManager.resolveStrategy(llmConfigId, userId);
        String llmResponse = strategy.generateSql(prompt, null);

        // 7. Parse LLM response as List<String> of table names
        List<String> filteredTableNames = parseTableNames(llmResponse);
        if (filteredTableNames.isEmpty()) {
            // Fallback: use all expanded tables if LLM parsing fails
            log.warn("[SchemaLinkingNode] Failed to parse LLM table filter response, using all {} tables", expandedTableList.size());
            filteredTableNames = expandedTableList;
        }

        // Validate filtered table names exist in the expanded table set
        Set<String> validTableNames = expandedTables.stream()
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
        filteredTableNames = filteredTableNames.stream()
                .filter(name -> validTableNames.contains(name.toLowerCase()))
                .collect(Collectors.toList());

        // If filtering removed all tables, fall back to the expanded set
        if (filteredTableNames.isEmpty()) {
            log.warn("[SchemaLinkingNode] All filtered tables were invalid, using expanded table set");
            filteredTableNames = expandedTableList;
        }

        log.info("[SchemaLinkingNode] LLM filtered tables: {} (from {} candidates)", filteredTableNames, expandedTableList.size());

        // 8. Re-expand filtered tables with FK relations (in case filter removed bridging tables)
        Set<String> finalTableSet = schemaRelationService.expandWithJoinTables(
                connectionId, new LinkedHashSet<>(filteredTableNames));
        List<ForeignKeyRelation> finalRelations = schemaRelationService.filterRelationsForTables(
                allRelations, finalTableSet);

        // 9. Build final filtered schema context (always full format for the filtered set)
        String filteredSchema = buildFullSchemaContext(connectionId, new ArrayList<>(finalTableSet), finalRelations);

        // 10. Build FK expressions string
        String fkExpressions = columnSampleService.buildForeignKeyExpressions(finalRelations);

        // Assemble final output
        StringBuilder tableRelation = new StringBuilder();
        tableRelation.append(filteredSchema);
        if (!fkExpressions.isBlank()) {
            tableRelation.append("\n【Foreign keys】\n").append(fkExpressions);
        }

        log.info("[SchemaLinkingNode] Schema linking complete: {} tables selected, output length={}",
                finalTableSet.size(), tableRelation.length());

        return Map.of(SqlAgentSpec.StateKey.TABLE_RELATION, tableRelation.toString());
    }

    /**
     * Get initial table names from state (user-selected tables) or from the database.
     */
    @SuppressWarnings("unchecked")
    private List<String> getInitialTableNames(OverAllState state, Long connectionId) {
        // Try to get user-selected table names from state
        Object tableNamesObj = state.value(SqlAgentSpec.StateKey.TABLE_NAMES, null);
        if (tableNamesObj instanceof List<?>) {
            List<String> tableNames = (List<String>) tableNamesObj;
            if (!tableNames.isEmpty()) {
                return tableNames;
            }
        }

        // Fall back to all tables in the database
        try {
            return databaseMetaDataService.getTableNames(connectionId);
        } catch (Exception e) {
            log.error("[SchemaLinkingNode] Failed to fetch table names for connectionId={}", connectionId, e);
            return Collections.emptyList();
        }
    }

    /**
     * Build full schema context with DDL + FK expressions + data samples.
     */
    private String buildFullSchemaContext(Long connectionId, List<String> tableNames,
                                           List<ForeignKeyRelation> relations) {
        StringBuilder sb = new StringBuilder();

        // DDL for each table
        for (String tableName : tableNames) {
            String ddl = databaseMetaDataService.getTableDDL(connectionId, tableName);
            if (ddl != null && !ddl.isBlank()) {
                sb.append(ddl).append("\n\n");
            }
        }

        // FK expressions
        String fkExpressions = columnSampleService.buildForeignKeyExpressions(relations);
        if (!fkExpressions.isBlank()) {
            sb.append("\n【Foreign keys】\n").append(fkExpressions).append("\n");
        }

        // Data samples
        String samples = columnSampleService.getColumnSamples(connectionId, tableNames);
        if (!samples.isBlank()) {
            sb.append("\n").append(samples);
        }

        return sb.toString();
    }

    /**
     * Build condensed schema context for large schemas.
     * Uses only table names + column names/types without full DDL or data samples.
     */
    private String buildCondensedSchemaContext(Long connectionId, List<String> tableNames,
                                                List<ForeignKeyRelation> relations) {
        StringBuilder sb = new StringBuilder();

        for (String tableName : tableNames) {
            sb.append("# Table: ").append(tableName).append("\n[\n");
            try {
                var columns = databaseMetaDataService.getTableColumns(connectionId, tableName);
                for (var col : columns) {
                    sb.append("(").append(col.getName()).append(": ").append(col.getDataType());
                    if (Boolean.TRUE.equals(col.getPrimaryKey())) {
                        sb.append(", Primary Key");
                    }
                    sb.append("),\n");
                }
            } catch (Exception e) {
                sb.append("(Error loading columns),\n");
            }
            sb.append("]\n\n");
        }

        // FK expressions
        String fkExpressions = columnSampleService.buildForeignKeyExpressions(relations);
        if (!fkExpressions.isBlank()) {
            sb.append("\n【Foreign keys】\n").append(fkExpressions).append("\n");
        }

        return sb.toString();
    }

    /**
     * Parse the LLM's mix-selector response into a list of table names.
     * <p>
     * Expected format: ["table1", "table2", ...]
     * Falls back to empty list if parsing fails.
     */
    private List<String> parseTableNames(String llmResponse) {
        if (llmResponse == null || llmResponse.isBlank()) {
            return Collections.emptyList();
        }

        // Strip markdown code fences
        String cleaned = MarkdownParserUtil.extractRawText(llmResponse);

        try {
            List<String> tableNames = objectMapper.readValue(cleaned, new TypeReference<List<String>>() {});
            if (tableNames != null && !tableNames.isEmpty()) {
                return tableNames.stream().distinct().collect(Collectors.toList());
            }
        } catch (Exception e) {
            log.debug("[SchemaLinkingNode] Failed to parse LLM response as JSON array: {}", e.getMessage());
            // Try to extract JSON array from surrounding text
            try {
                int startIdx = cleaned.indexOf('[');
                int endIdx = cleaned.lastIndexOf(']');
                if (startIdx >= 0 && endIdx > startIdx) {
                    String jsonArray = cleaned.substring(startIdx, endIdx + 1);
                    List<String> tableNames = objectMapper.readValue(jsonArray, new TypeReference<List<String>>() {});
                    if (tableNames != null && !tableNames.isEmpty()) {
                        return tableNames.stream().distinct().collect(Collectors.toList());
                    }
                }
            } catch (Exception e2) {
                log.warn("[SchemaLinkingNode] Failed to extract JSON array from LLM response: {}", e2.getMessage());
            }
        }

        return Collections.emptyList();
    }
}