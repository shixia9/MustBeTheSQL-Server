package com.sql.logic.engine.application.service;

import com.sql.logic.engine.domain.agent.strategy.LLMStrategy;
import com.sql.logic.engine.domain.agent.strategy.LLMStrategyContext;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
public class SQLGenerateAppService {

    private final LLMStrategyContext llmStrategyContext;
    private final UserAppService userAppService;

    public SQLGenerateAppService(LLMStrategyContext llmStrategyContext, UserAppService userAppService) {
        this.llmStrategyContext = llmStrategyContext;
        this.userAppService = userAppService;
    }

    public Flux<String> generateSqlStream(Long userId, String userInput, String schemaContext, String strategyName) {
        // Check user status and deduct AI token quota
        try {
            userAppService.checkAndDeductToken(userId);
        } catch (Exception e) {
            return Flux.error(e);
        }

        String prompt = buildPrompt(userInput, schemaContext);
        LLMStrategy strategy = llmStrategyContext.getStrategy(strategyName);
        return strategy.generateSqlStream(prompt);
    }

    private String buildPrompt(String userInput, String schemaContext) {
        return "You are an expert SQL Generator.\n" +
               "Given the following database schema:\n" + schemaContext + "\n\n" +
               "Generate a pure SQL query to answer the following user request: " + userInput + "\n" +
               "Output ONLY the SQL code, nothing else.";
    }
}
