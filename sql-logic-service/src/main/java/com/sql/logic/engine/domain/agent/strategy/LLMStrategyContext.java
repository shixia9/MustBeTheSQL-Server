package com.sql.logic.engine.domain.agent.strategy;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class LLMStrategyContext {

    private final Map<String, LLMStrategy> strategyMap = new ConcurrentHashMap<>();

    public LLMStrategyContext(Map<String, LLMStrategy> strategies) {
        this.strategyMap.putAll(strategies);
    }

    public LLMStrategy getStrategy(String strategyName) {
        LLMStrategy strategy = strategyMap.get(strategyName);
        if (strategy == null) {
            // Default to OpenAI if not found
            return strategyMap.get("openAiStrategy");
        }
        return strategy;
    }
}
