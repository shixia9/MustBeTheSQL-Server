package com.sql.logic.engine.domain.agentic.action;

import com.sql.logic.engine.domain.agent.SqlAgentSpec;
import com.sql.logic.engine.domain.agent.prompt.PromptManager;
import com.sql.logic.engine.domain.agentic.core.*;
import com.sql.logic.engine.domain.agentic.resource.AgentResource;
import com.sql.logic.engine.infrastructure.util.MarkdownParserUtil;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Generates a SQL statement from the current message context.
 * <p>
 * Renders the {@code new-sql-generate.st} prompt template (same as the existing
 * {@code SqlGenerationNode}), calls the Agent's bound LLM strategy, and extracts
 * the clean SQL from the LLM response.
 * <p>
 * Registered as the primary action on {@code DataScientistAgent}.
 */
public class SqlGenerationAction implements AgentAction {

    private final PromptManager promptManager;

    public SqlGenerationAction(PromptManager promptManager) {
        this.promptManager = promptManager;
    }

    @Override
    public String name() {
        return "sql_generation";
    }

    @Override
    public String description() {
        return "生成 SQL 查询语句";
    }

    @Override
    public CompletableFuture<ActionOutput> execute(AgentMessage context, Agent agent) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ConversableAgent ca = (ConversableAgent) agent;
                AgentResource resource = ca.getResource();

                // Build prompt variables from context
                Map<String, Object> vars = new HashMap<>();
                vars.put("dialect", context.context().getOrDefault("dialect", "MySQL"));
                vars.put("question", context.content());
                vars.put("schema_info", context.context().getOrDefault("schemaInfo", ""));
                vars.put("evidence", context.context().getOrDefault("evidence", ""));
                vars.put("execution_description", context.context().getOrDefault("executionDescription", ""));

                // Conversation history
                String conversationHistory = (String) context.context().getOrDefault("conversationHistory", "");
                vars.put("conversation_history_section",
                        conversationHistory.isEmpty() ? "" : "## 对话历史\n" + conversationHistory);

                // User memory
                String userMemory = (String) context.context().getOrDefault("userMemory", "");
                vars.put("user_memory_section",
                        userMemory.isEmpty() ? "" : "## 用户偏好\n" + userMemory);

                // Agent system prompt
                String agentSystemPrompt = (String) context.context().getOrDefault("agentSystemPrompt", "");
                vars.put("system_prompt_section",
                        agentSystemPrompt.isEmpty() ? "" : "## 系统提示\n" + agentSystemPrompt);

                // Resource context
                if (resource != null) {
                    String resourcePrompt = resource.getPrompt(context);
                    vars.put("schema_info",
                            vars.get("schema_info") + "\n" + resourcePrompt);
                }

                String renderedPrompt = promptManager.render(
                        SqlAgentSpec.PromptName.NEW_SQL_GENERATE, vars
                );

                String rawSql = ca.resolveLlmStrategy().generateSql(renderedPrompt, null);
                String cleanSql = MarkdownParserUtil.extractRawText(rawSql);

                return ActionOutput.success(cleanSql, Map.of("sql", cleanSql));
            } catch (Exception e) {
                return ActionOutput.fail("SQL generation failed: " + e.getMessage());
            }
        });
    }
}
