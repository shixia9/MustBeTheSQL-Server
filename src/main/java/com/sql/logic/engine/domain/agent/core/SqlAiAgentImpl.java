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
    public Flux<String> generateSqlStream(String userInput, Long connectionId, List<String> tableNames, String manualContext, BiConsumer<Integer, String> tokenAndSqlCallback) {
        String dynamicSchemaContext = "";
        if (schemaContextProvider != null) {
            dynamicSchemaContext = schemaContextProvider.buildDynamicSchemaContext(connectionId, tableNames);
        }
        
        String finalSchemaContext = dynamicSchemaContext;
        if (manualContext != null && !manualContext.isEmpty()) {
            finalSchemaContext += "\nAdditional Context: " + manualContext;
        }

        String prompt = buildPrompt(userInput, finalSchemaContext);
        
        return llmStrategy.generateSqlStream(prompt, tokenAndSqlCallback);
    }

    @Override
    public LLMStrategy getLlmStrategy() {
        return this.llmStrategy;
    }

    private String buildPrompt(String userInput, String schemaContext) {
        return "You are an expert SQL Generator and Database Assistant.\n" +
               "Given the following database schema:\n" + schemaContext + "\n\n" +
               "Based on the user's request, generate the correct SQL query and a brief explanation.\n" +
               "IMPORTANT: You MUST format your response EXACTLY as a valid JSON object with two keys: \"explain\" and \"sql\".\n" +
               "DO NOT use markdown code blocks (```json or ```sql) or any other text outside the JSON object.\n" +
               "Example:\n" +
               "{\n" +
               "  \"explain\": \"This query selects all active users.\",\n" +
               "  \"sql\": \"SELECT * FROM users WHERE status = 'active';\"\n" +
               "}\n\n" +
               "User request: " + userInput;
    }
}