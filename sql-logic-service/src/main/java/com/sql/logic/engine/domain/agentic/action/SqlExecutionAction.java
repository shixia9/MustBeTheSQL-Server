package com.sql.logic.engine.domain.agentic.action;

import com.sql.logic.engine.domain.agent.model.SqlExecutionResult;
import com.sql.logic.engine.domain.agent.service.SqlExecutionService;
import com.sql.logic.engine.domain.agentic.core.*;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Executes a generated SQL statement against the target database.
 * <p>
 * Delegates to the existing {@link SqlExecutionService} for read-only
 * execution with safety gates (SELECT-only, row limit, timeout).
 */
public class SqlExecutionAction implements AgentAction {

    private final SqlExecutionService executionService;

    public SqlExecutionAction(SqlExecutionService executionService) {
        this.executionService = executionService;
    }

    @Override
    public String name() {
        return "sql_execution";
    }

    @Override
    public String description() {
        return "执行 SQL 查询并返回结果";
    }

    @Override
    public CompletableFuture<ActionOutput> execute(AgentMessage context, Agent agent) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Extract SQL from context (set by SqlGenerationAction)
                String sql = context.content();
                if (sql == null || sql.isBlank()) {
                    // Try to get it from the context map
                    sql = (String) context.context().getOrDefault("sql", "");
                }
                if (sql == null || sql.isBlank()) {
                    return ActionOutput.fail("No SQL to execute");
                }

                Long userId = toLong(context.context().get("userId"));
                Long connectionId = toLong(context.context().get("connectionId"));
                String schemaName = (String) context.context().get("schemaName");

                if (connectionId == null) {
                    return ActionOutput.fail("No database connection configured");
                }

                SqlExecutionResult result = executionService.execute(
                        userId != null ? userId : 0L,
                        connectionId,
                        sql,
                        schemaName
                );

                if (result.hasError()) {
                    return ActionOutput.fail("SQL execution error: " + result.getErrorMsg(), true);
                }

                return ActionOutput.success(
                        "Query returned " + result.getRowCount() + " rows",
                        Map.of(
                                "columns", result.getColumns() != null ? result.getColumns() : java.util.List.of(),
                                "rows", result.getRows() != null ? result.getRows() : java.util.List.of(),
                                "rowCount", result.getRowCount()
                        )
                );
            } catch (Exception e) {
                return ActionOutput.fail("SQL execution failed: " + e.getMessage(), true);
            }
        });
    }

    private static Long toLong(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(obj));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
