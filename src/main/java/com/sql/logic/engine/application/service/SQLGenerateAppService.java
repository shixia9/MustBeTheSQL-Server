package com.sql.logic.engine.application.service;

import com.sql.logic.engine.domain.agent.strategy.LLMStrategy;
import com.sql.logic.engine.domain.agent.strategy.LLMStrategyContext;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;

@Service
public class SQLGenerateAppService {

    private final LLMStrategyContext llmStrategyContext;
    private final UserAppService userAppService;
    private final DatabaseMetaDataService databaseMetaDataService;

    public SQLGenerateAppService(LLMStrategyContext llmStrategyContext, 
                                 UserAppService userAppService,
                                 DatabaseMetaDataService databaseMetaDataService) {
        this.llmStrategyContext = llmStrategyContext;
        this.userAppService = userAppService;
        this.databaseMetaDataService = databaseMetaDataService;
    }

    public Flux<String> generateSqlStream(Long userId, String userInput, Long connectionId, List<String> tableNames, String manualSchemaContext, String strategyName) {
        // Check user status and AI token quota before generation
        try {
            userAppService.checkBeforeGeneration(userId);
        } catch (Exception e) {
            return Flux.error(e);
        }

        String dynamicSchemaContext = buildDynamicSchemaContext(connectionId, tableNames);
        String finalSchemaContext = dynamicSchemaContext;
        if (manualSchemaContext != null && !manualSchemaContext.isEmpty()) {
            finalSchemaContext += "\nAdditional Context: " + manualSchemaContext;
        }

        String prompt = buildPrompt(userInput, finalSchemaContext);
        LLMStrategy strategy = llmStrategyContext.getStrategy(strategyName);
        
        // Pass the token deduction callback which triggers precisely after completion
        return strategy.generateSqlStream(prompt, (tokens) -> {
            try {
                userAppService.deductTokens(userId, tokens);
            } catch (Exception e) {
                System.err.println("Audit Log: Token deduction exception for user " + userId + ": " + e.getMessage());
            }
        });
    }

    private String buildDynamicSchemaContext(Long connectionId, List<String> tableNames) {
        if (connectionId == null || tableNames == null || tableNames.isEmpty()) {
            return "No specific database schema provided.";
        }
        StringBuilder sb = new StringBuilder("Database Schema Context:\n");
        for (String tableName : tableNames) {
            try {
                String ddl = databaseMetaDataService.getTableDDL(connectionId, tableName);
                if (ddl != null && !ddl.isEmpty()) {
                    sb.append(ddl).append("\n\n");
                }
            } catch (Exception e) {
                System.err.println("Failed to fetch DDL for table: " + tableName);
            }
        }
        return sb.toString();
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
