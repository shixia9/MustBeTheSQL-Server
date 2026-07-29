package com.sql.logic.engine.domain.agentic.action;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sql.logic.engine.domain.agent.model.SqlExecutionResult;
import com.sql.logic.engine.domain.agent.service.SqlExecutionService;
import com.sql.logic.engine.domain.agentic.core.*;
import com.sql.logic.engine.domain.agentic.vis.VisChart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Chart generation action — executes a SQL query and produces structured
 * chart data (type, data, title) wrapped as a vis-db-chart code fence.
 * 1. Parse LLM output for display_type + sql
 * 2. Execute SQL via SqlExecutionService
 * 3. Build VisChart params → wrap in markdown code fence
 */
public class ChartAction implements AgentAction {

    private static final Logger log = LoggerFactory.getLogger(ChartAction.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final SqlExecutionService sqlExecutionService;
    private final VisChart visChart;

    public ChartAction(SqlExecutionService sqlExecutionService, VisChart visChart) {
        this.sqlExecutionService = sqlExecutionService;
        this.visChart = visChart;
    }

    @Override
    public String name() { return "chart"; }

    @Override
    public String description() { return "Execute SQL and generate chart visualization"; }

    @Override
    public CompletableFuture<ActionOutput> execute(AgentMessage context, Agent agent) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Parse the LLM output: expect { display_type, sql, thought }
                SqlInput input = parseSqlInput(context.content());

                if (input.sql == null || input.sql.isBlank()) {
                    // No SQL in content — extract from previous action output
                    ActionOutput prev = context.actionReport();
                    if (prev != null && prev.content() != null) {
                        input = new SqlInput(
                                prev.content(),
                                input.displayType != null ? input.displayType : "response_table",
                                input.thought != null ? input.thought : ""
                        );
                    }
                }

                if (input.sql == null || input.sql.isBlank()) {
                    return ActionOutput.fail("No SQL found for chart generation");
                }

                // Extract identity fields from message context
                Long ctxUserId = toLong(context.context().get("userId"));
                Long ctxConnectionId = toLong(context.context().get("connectionId"));

                // Execute the SQL
                Map<String, Object> execResult;
                try {
                    SqlExecutionResult result = sqlExecutionService.execute(ctxUserId, ctxConnectionId, input.sql);
                    if (result.hasError()) {
                        log.warn("[ChartAction] SQL execution error: {}", result.getErrorMsg());
                        return new ActionOutput(false,
                                "Chart SQL execution failed: " + result.getErrorMsg(),
                                Map.of("sql", input.sql, "error", result.getErrorMsg()),
                                List.of(), true);
                    }
                    execResult = new LinkedHashMap<>();
                    execResult.put("rows", result.getRows());
                    List<Map<String, String>> colMaps = new ArrayList<>();
                    for (String col : result.getColumns()) {
                        colMaps.add(Map.of("name", col));
                    }
                    execResult.put("columns", colMaps);
                } catch (Exception e) {
                    log.warn("[ChartAction] SQL execution failed: {}", e.getMessage());
                    return new ActionOutput(false,
                            "Chart SQL execution failed: " + e.getMessage(),
                            Map.of("sql", input.sql, "error", e.getMessage()),
                            List.of(), true);
                }

                @SuppressWarnings("unchecked")
                List<Map<String, Object>> rows =
                        (List<Map<String, Object>>) execResult.getOrDefault("rows", List.of());
                @SuppressWarnings("unchecked")
                List<Map<String, String>> columns =
                        (List<Map<String, String>>) execResult.getOrDefault("columns", List.of());

                // Generate chart title
                String title = input.thought != null && !input.thought.isBlank()
                        ? input.thought : "Query Result";

                // Build vis-chart output
                String visOutput = visChart.render(
                        input.sql, input.displayType, title,
                        input.thought, rows, columns);

                Map<String, Object> data = new LinkedHashMap<>();
                data.put("sql", input.sql);
                data.put("displayType", input.displayType);
                data.put("title", title);
                data.put("rows", rows);
                data.put("columns", columns);

                log.info("[ChartAction] Generated chart: type={}, rows={}",
                        input.displayType, rows.size());

                return ActionOutput.success(visOutput, data);

            } catch (Exception e) {
                log.error("[ChartAction] Failed", e);
                return ActionOutput.fail("Chart generation failed: " + e.getMessage());
            }
        });
    }

    /**
     * Parse LLM output as SqlInput. Handles both raw JSON and markdown-wrapped JSON.
     */
    private SqlInput parseSqlInput(String content) {
        if (content == null || content.isBlank()) {
            return new SqlInput(null, "response_table", "");
        }
        try {
            // Try direct JSON parse
            String json = content.trim();
            // Strip markdown code fences if present
            if (json.startsWith("```")) {
                json = json.replaceAll("```[a-z]*\n?", "").replaceAll("\n```", "");
            }
            Map<String, Object> map = mapper.readValue(json,
                    new TypeReference<Map<String, Object>>() {});
            return new SqlInput(
                    (String) map.getOrDefault("sql", map.get("query")),
                    (String) map.getOrDefault("display_type", "response_table"),
                    (String) map.getOrDefault("thought", "")
            );
        } catch (Exception e) {
            // Not valid JSON — the entire content might be SQL
            String upper = content.trim().toUpperCase();
            if (upper.startsWith("SELECT") || upper.startsWith("WITH")) {
                return new SqlInput(content.trim(), "response_table", "");
            }
            return new SqlInput(null, "response_table", "");
        }
    }

    private record SqlInput(String sql, String displayType, String thought) {}

    private static Long toLong(Object val) {
        if (val instanceof Number n) return n.longValue();
        if (val instanceof String s && !s.isBlank()) {
            try { return Long.parseLong(s); } catch (NumberFormatException ignored) {}
        }
        return null;
    }
}
