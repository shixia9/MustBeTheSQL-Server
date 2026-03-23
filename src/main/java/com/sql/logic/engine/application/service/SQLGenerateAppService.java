package com.sql.logic.engine.application.service;

import com.sql.logic.engine.domain.agent.strategy.LLMStrategy;
import com.sql.logic.engine.domain.agent.strategy.LLMStrategyContext;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
public class SQLGenerateAppService {

    private final LLMStrategyContext llmStrategyContext;

    public SQLGenerateAppService(LLMStrategyContext llmStrategyContext) {
        this.llmStrategyContext = llmStrategyContext;
    }

    public Flux<String> generateSqlStream(String userInput, String schemaContext, String strategyName) {
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
