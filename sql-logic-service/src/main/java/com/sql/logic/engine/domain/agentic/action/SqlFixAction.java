package com.sql.logic.engine.domain.agentic.action;

import com.sql.logic.engine.domain.agent.SqlAgentSpec;
import com.sql.logic.engine.domain.agent.prompt.PromptManager;
import com.sql.logic.engine.domain.agentic.core.*;
import com.sql.logic.engine.infrastructure.util.MarkdownParserUtil;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Fixes a failed SQL statement by calling the LLM with error context.
 * <p>
 * Renders the {@code sql-error-fixer.st} prompt template with the original SQL,
 * the execution error message, and the schema context, then extracts the fixed SQL.
 * This mirrors the existing {@code SqlFixerNode} behavior.
 */
public class SqlFixAction implements AgentAction {

    private final PromptManager promptManager;

    public SqlFixAction(PromptManager promptManager) {
        this.promptManager = promptManager;
    }

    @Override
    public String name() {
        return "sql_fix";
    }

    @Override
    public String description() {
        return "修复执行失败的 SQL 语句";
    }

    @Override
    public CompletableFuture<ActionOutput> execute(AgentMessage context, Agent agent) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ConversableAgent ca = (ConversableAgent) agent;

                // Get the failed SQL and error from context
                String originalSql = (String) context.context().getOrDefault("originalSql", context.content());
                String errorMsg = (String) context.context().getOrDefault("errorMessage", "");
                String schemaInfo = (String) context.context().getOrDefault("schemaInfo", "");
                String dialect = (String) context.context().getOrDefault("dialect", "MySQL");

                Map<String, Object> vars = new HashMap<>();
                vars.put("dialect", dialect);
                vars.put("original_sql", originalSql);
                vars.put("error_message", errorMsg);
                vars.put("schema_info", schemaInfo);
                vars.put("question", context.context().getOrDefault("question", ""));

                String renderedPrompt = promptManager.render(
                        SqlAgentSpec.PromptName.SQL_ERROR_FIXER, vars
                );

                String rawSql = ca.getLlmStrategy().generateSql(renderedPrompt, null);
                String fixedSql = MarkdownParserUtil.extractRawText(rawSql);

                return ActionOutput.success(fixedSql, Map.of(
                        "sql", fixedSql,
                        "originalSql", originalSql,
                        "wasFixed", true
                ));
            } catch (Exception e) {
                return ActionOutput.fail("SQL fix failed: " + e.getMessage(), false);
            }
        });
    }
}
