package com.sql.logic.engine.domain.agentic.enrichment;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sql.logic.engine.application.service.ColumnSampleService;
import com.sql.logic.engine.application.service.DatabaseMetaDataService;
import com.sql.logic.engine.application.service.SchemaRelationService;
import com.sql.logic.engine.domain.agent.SqlAgentSpec;
import com.sql.logic.engine.domain.agent.core.LlmClientManager;
import com.sql.logic.engine.domain.agent.dto.ForeignKeyRelation;
import com.sql.logic.engine.domain.agent.ha.LlmCallReporter;
import com.sql.logic.engine.domain.agent.prompt.PromptManager;
import com.sql.logic.engine.domain.agent.strategy.LLMStrategy;
import com.sql.logic.engine.domain.trace.TraceContext;
import com.sql.logic.engine.infrastructure.util.MarkdownParserUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Background schema enrichment via LLM semantic table filtering.
 * <p>
 * The {@link #enrich} method is designed to run on a background thread
 * while the ManagerAgent handles planning. By the time downstream agents
 * (e.g. DataScientistAgent) need schema context the filtered DDL is
 * typically ready, hiding the LLM filtering latency and reducing token
 * consumption for large schemas.
 */
@Service
public class SchemaEnrichmentService {

    private static final Logger log = LoggerFactory.getLogger(SchemaEnrichmentService.class);

    private static final int LARGE_SCHEMA_THRESHOLD = 20;

    private final DatabaseMetaDataService databaseMetaDataService;
    private final SchemaRelationService schemaRelationService;
    private final ColumnSampleService columnSampleService;
    private final LlmClientManager llmClientManager;
    private final PromptManager promptManager;
    private final LlmCallReporter llmCallReporter;
    private final ObjectMapper objectMapper;

    public SchemaEnrichmentService(DatabaseMetaDataService databaseMetaDataService,
                                   SchemaRelationService schemaRelationService,
                                   ColumnSampleService columnSampleService,
                                   LlmClientManager llmClientManager,
                                   PromptManager promptManager,
                                   LlmCallReporter llmCallReporter,
                                   ObjectMapper objectMapper) {
        this.databaseMetaDataService = databaseMetaDataService;
        this.schemaRelationService = schemaRelationService;
        this.columnSampleService = columnSampleService;
        this.llmClientManager = llmClientManager;
        this.promptManager = promptManager;
        this.llmCallReporter = llmCallReporter;
        this.objectMapper = objectMapper;
    }

    /**
     * Build a quick schema summary without LLM filtering.
     * Returns a compact table-name listing suitable as a fallback while
     * async enrichment is still in progress.
     */
    private static final int QUICK_FALLBACK_DDL_MAX_TABLES = 200;

    public String buildQuickFallback(Long connectionId, List<String> tableNames, String schemaName) {
        if (connectionId == null || connectionId <= 0) return "";

        try {
            String effectiveSchema = resolveSchema(connectionId, schemaName);
            List<String> tables = resolveTables(connectionId, tableNames, effectiveSchema);
            if (tables.isEmpty()) {
                return "Database connection #" + connectionId + " — no tables discovered.";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Database connection #").append(connectionId);
            if (effectiveSchema != null) sb.append(" · Schema: ").append(effectiveSchema);
            sb.append("\n\n");

            // Fetch column-level DDL for the resolved tables so the LLM knows
            // actual column names/types immediately (avoids "Unknown column" errors).
            int ddlCount = Math.min(tables.size(), QUICK_FALLBACK_DDL_MAX_TABLES);
            for (int i = 0; i < ddlCount; i++) {
                try {
                    String ddl = databaseMetaDataService.getTableDDL(connectionId, effectiveSchema, tables.get(i));
                    if (ddl != null && !ddl.isBlank()) {
                        sb.append(ddl).append("\n");
                    } else {
                        sb.append("-- ").append(tables.get(i)).append(" (no DDL available)\n");
                    }
                } catch (Exception ignored) {
                    sb.append("-- ").append(tables.get(i)).append(" (DDL fetch failed)\n");
                }
            }

            if (tables.size() > QUICK_FALLBACK_DDL_MAX_TABLES) {
                sb.append("\n_(").append(tables.size()).append(" tables total — ")
                        .append(tables.size() - QUICK_FALLBACK_DDL_MAX_TABLES)
                        .append(" omitted; full schema enrichment in progress)_\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("[SchemaEnrichment] Quick fallback failed: {}", e.getMessage());
            return "";
        }
    }

    /**
     * Run the full LLM-based schema enrichment pipeline.
     * <p>
     * Pipeline:
     * 1. Collect table names (from parameter or DB)
     * 2. Expand with FK-connected tables
     * 3. Build schema context (full DDL or condensed columns for large schemas)
     * 4. LLM semantic filtering via mix-selector prompt
     * 5. Parse filtered table names from LLM response
     * 6. Re-expand filtered set with FK relations
     * 7. Build and return final DDL for the filtered set
     * <p>
     * On any failure, falls back to the full (unfiltered) DDL.
     *
     * @return enriched schema DDL string, or full DDL fallback on failure
     */
    public String enrich(Long connectionId, List<String> tableNames, String schemaName,
                         String userQuestion, String conversationHistory, String evidence,
                         Long llmConfigId, Long userId, TraceContext traceContext) {
        if (connectionId == null || connectionId <= 0) return "";

        String effectiveSchema;
        List<String> tables;
        try {
            effectiveSchema = resolveSchema(connectionId, schemaName);
            tables = resolveTables(connectionId, tableNames, effectiveSchema);
        } catch (Exception e) {
            log.warn("[SchemaEnrichment] Failed to resolve tables: {}", e.getMessage());
            return "";
        }

        if (tables.isEmpty()) {
            log.warn("[SchemaEnrichment] No tables found for connectionId={}", connectionId);
            return "";
        }

        try {
            // 1. FK expansion
            Set<String> expandedTables = schemaRelationService.expandWithJoinTables(
                    connectionId, new LinkedHashSet<>(tables), effectiveSchema);
            List<String> expandedList = new ArrayList<>(expandedTables);
            log.info("[SchemaEnrichment] Expanded tables: {} -> {}", tables.size(), expandedList.size());

            // 2. FK relations
            List<ForeignKeyRelation> allRelations = schemaRelationService.getForeignKeyRelations(
                    connectionId, effectiveSchema);
            List<ForeignKeyRelation> relevantRelations = schemaRelationService.filterRelationsForTables(
                    allRelations, expandedTables);

            // 3. Build schema context
            boolean isLarge = expandedList.size() > LARGE_SCHEMA_THRESHOLD;
            String schemaContext;
            if (isLarge) {
                schemaContext = buildCondensed(connectionId, effectiveSchema, expandedList, relevantRelations);
            } else {
                schemaContext = buildFull(connectionId, effectiveSchema, expandedList, relevantRelations, false);
            }

            // 4. Render mix-selector prompt
            String prompt = promptManager.render(SqlAgentSpec.PromptName.MIX_SELECTOR, Map.of(
                    "schema_info", schemaContext,
                    "question", userQuestion != null ? userQuestion : "",
                    "evidence", evidence != null && !evidence.isBlank() ? evidence : "",
                    "conversation_history", conversationHistory != null ? conversationHistory : ""
            ));

            // 5. LLM call for table filtering
            LLMStrategy strategy = llmClientManager.resolveTraced(llmConfigId, userId,
                    traceContext, "SCHEMA_ENRICHMENT", llmCallReporter);
            String llmResponse = strategy.generateSql(prompt, null);

            // 6. Parse filtered tables
            List<String> filtered = parseTableNames(llmResponse);
            if (filtered.isEmpty()) {
                log.info("[SchemaEnrichment] LLM filtering produced empty result, using all {} tables",
                        expandedList.size());
                filtered = expandedList;
            }

            // Validate against expanded set
            Set<String> validLower = expandedTables.stream()
                    .map(String::toLowerCase).collect(Collectors.toSet());
            filtered = filtered.stream()
                    .filter(n -> validLower.contains(n.toLowerCase()))
                    .collect(Collectors.toList());
            if (filtered.isEmpty()) filtered = expandedList;

            log.info("[SchemaEnrichment] LLM filtered: {} tables (from {} candidates)",
                    filtered.size(), expandedList.size());

            // 7. Re-expand filtered set with FK
            Set<String> finalSet = schemaRelationService.expandWithJoinTables(
                    connectionId, new LinkedHashSet<>(filtered), effectiveSchema);
            List<ForeignKeyRelation> finalRelations = schemaRelationService.filterRelationsForTables(
                    allRelations, finalSet);

            // 8. Build final enriched DDL
            String enriched = buildFull(connectionId, effectiveSchema,
                    new ArrayList<>(finalSet), finalRelations, false);

            String fkExpr = columnSampleService.buildForeignKeyExpressions(finalRelations);
            if (!fkExpr.isBlank()) {
                enriched += "\n【Foreign keys】\n" + fkExpr;
            }

            log.info("[SchemaEnrichment] Enriched schema: {} tables, {} chars",
                    finalSet.size(), enriched.length());
            return enriched;

        } catch (Exception e) {
            log.warn("[SchemaEnrichment] Enrichment failed, falling back to full DDL: {}", e.getMessage());
            return buildFullDdlFallback(connectionId, effectiveSchema, tables);
        }
    }

    // ---- internal helpers ----

    private String resolveSchema(Long connectionId, String schemaName) {
        if (schemaName != null && !schemaName.isBlank()) return schemaName;
        try {
            List<String> schemas = databaseMetaDataService.getSchemas(connectionId);
            if (!schemas.isEmpty()) return schemas.get(0);
        } catch (Exception ignored) {}
        return null;
    }

    private List<String> resolveTables(Long connectionId, List<String> tableNames, String schemaName) {
        if (tableNames != null && !tableNames.isEmpty()) return tableNames;
        return databaseMetaDataService.getTableNames(connectionId, schemaName);
    }

    private String buildFull(Long connectionId, String schemaName, List<String> tableNames,
                             List<ForeignKeyRelation> relations, boolean includeSamples) {
        StringBuilder sb = new StringBuilder();
        for (String tableName : tableNames) {
            try {
                String ddl = databaseMetaDataService.getTableDDL(connectionId, schemaName, tableName);
                if (ddl != null && !ddl.isBlank()) {
                    sb.append(ddl).append("\n\n");
                }
            } catch (Exception ignored) {}
        }
        String fkExpr = columnSampleService.buildForeignKeyExpressions(relations);
        if (!fkExpr.isBlank()) {
            sb.append("\n【Foreign keys】\n").append(fkExpr).append("\n");
        }
        if (includeSamples) {
            String samples = columnSampleService.getColumnSamples(connectionId, tableNames, schemaName);
            if (!samples.isBlank()) sb.append("\n").append(samples);
        }
        return sb.toString();
    }

    private String buildCondensed(Long connectionId, String schemaName, List<String> tableNames,
                                  List<ForeignKeyRelation> relations) {
        StringBuilder sb = new StringBuilder();
        for (String tableName : tableNames) {
            sb.append("# Table: ").append(tableName).append("\n[\n");
            try {
                var columns = databaseMetaDataService.getTableColumns(connectionId, schemaName, tableName);
                for (var col : columns) {
                    sb.append("(").append(col.getName()).append(": ").append(col.getDataType());
                    if (Boolean.TRUE.equals(col.getPrimaryKey())) sb.append(", Primary Key");
                    sb.append("),\n");
                }
            } catch (Exception e) {
                sb.append("(Error loading columns),\n");
            }
            sb.append("]\n\n");
        }
        String fkExpr = columnSampleService.buildForeignKeyExpressions(relations);
        if (!fkExpr.isBlank()) {
            sb.append("\n【Foreign keys】\n").append(fkExpr).append("\n");
        }
        return sb.toString();
    }

    private String buildFullDdlFallback(Long connectionId, String schemaName, List<String> tableNames) {
        StringBuilder sb = new StringBuilder();
        sb.append("Database connection #").append(connectionId).append(":\n\n");
        for (String table : tableNames) {
            try {
                String ddl = databaseMetaDataService.getTableDDL(connectionId, schemaName, table);
                if (ddl != null && !ddl.isBlank()) sb.append(ddl).append("\n");
            } catch (Exception ignored) {}
        }
        return sb.toString();
    }

    private List<String> parseTableNames(String llmResponse) {
        if (llmResponse == null || llmResponse.isBlank()) return Collections.emptyList();
        String cleaned = MarkdownParserUtil.extractRawText(llmResponse);
        try {
            List<String> names = objectMapper.readValue(cleaned, new TypeReference<List<String>>() {});
            if (names != null && !names.isEmpty()) {
                return names.stream().distinct().collect(Collectors.toList());
            }
        } catch (Exception e) {
            int start = cleaned.indexOf('[');
            int end = cleaned.lastIndexOf(']');
            if (start >= 0 && end > start) {
                try {
                    String jsonArray = cleaned.substring(start, end + 1);
                    List<String> names = objectMapper.readValue(jsonArray, new TypeReference<List<String>>() {});
                    if (names != null && !names.isEmpty()) {
                        return names.stream().distinct().collect(Collectors.toList());
                    }
                } catch (Exception ignored) {}
            }
        }
        return Collections.emptyList();
    }
}
