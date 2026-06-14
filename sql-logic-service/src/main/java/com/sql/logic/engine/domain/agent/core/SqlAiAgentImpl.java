package com.sql.logic.engine.domain.agent.core;

import com.sql.logic.engine.domain.agent.strategy.LLMStrategy;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.function.BiConsumer;

public class SqlAiAgentImpl implements AiAgent {

    private final LLMStrategy llmStrategy;
    private SchemaContextProvider schemaContextProvider;

    public SqlAiAgentImpl(LLMStrategy llmStrategy) {
        this.llmStrategy = llmStrategy;
    }

    @Override
    public void setSchemaContextProvider(SchemaContextProvider provider) {
        this.schemaContextProvider = provider;
    }

    @Override
    public Flux<String> generateSqlStream(String userInput, Long connectionId, List<String> tableNames,
                                           String manualContext, BiConsumer<Integer, String> tokenAndSqlCallback) {
        return generateSqlStream(userInput, connectionId, tableNames, manualContext, tokenAndSqlCallback, llmStrategy);
    }

    @Override
    public Flux<String> generateSqlStream(String userInput, Long connectionId, List<String> tableNames,
                                           String manualContext, BiConsumer<Integer, String> tokenAndSqlCallback,
                                           LLMStrategy strategy) {
        String dynamicSchemaContext = "";
        if (schemaContextProvider != null) {
            dynamicSchemaContext = schemaContextProvider.buildDynamicSchemaContext(connectionId, tableNames);
        }

        String finalSchemaContext = dynamicSchemaContext;
        if (manualContext != null && !manualContext.isEmpty()) {
            finalSchemaContext += "\nAdditional Context: " + manualContext;
        }

        String prompt = buildPrompt(userInput, finalSchemaContext);

        return strategy.generateSqlStream(prompt, tokenAndSqlCallback);
    }

    @Override
    public LLMStrategy getLlmStrategy() {
        return this.llmStrategy;
    }

    private String buildPrompt(String userInput, String schemaContext) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are an expert SQL Generator and Database Assistant.\n\n");

        if (schemaContext != null && !schemaContext.isEmpty()) {
            prompt.append("### Database Schema\n");
            prompt.append("The following tables are available in the database:\n\n");
            prompt.append(schemaContext);
            prompt.append("\n\n");
        }

        prompt.append("### Instructions\n");
        prompt.append("Based on the user's request, generate the correct SQL query and a brief explanation.\n\n");
        prompt.append("Rules:\n");
        prompt.append("1. Generate ONLY the SQL query that directly answers the user's request.\n");
        prompt.append("2. Do NOT generate DDL statements (CREATE, ALTER, DROP) unless explicitly requested.\n");
        prompt.append("3. Add appropriate WHERE clauses, JOINs, and aggregations as needed.\n");
        prompt.append("4. Use table aliases when joining tables to prevent ambiguity.\n");
        prompt.append("5. If the user's request is ambiguous, make reasonable assumptions and explain them.\n");
        prompt.append("6. Prefer SELECT queries for analysis; for data modifications, confirm intent.\n\n");

        prompt.append("### Response Format\n");
        prompt.append("IMPORTANT: You MUST format your response EXACTLY as a valid JSON object with two keys: \"explain\" and \"sql\".\n");
        prompt.append("DO NOT use markdown code blocks (```json or ```sql) or any other text outside the JSON object.\n\n");
        prompt.append("Example:\n");
        prompt.append("{\n");
        prompt.append("  \"explain\": \"This query selects all active users ordered by creation date.\",\n");
        prompt.append("  \"sql\": \"SELECT * FROM users WHERE status = 'active' ORDER BY created_at DESC\"\n");
        prompt.append("}\n\n");

        prompt.append("### User Request\n");
        prompt.append(userInput);

        return prompt.toString();
    }
}