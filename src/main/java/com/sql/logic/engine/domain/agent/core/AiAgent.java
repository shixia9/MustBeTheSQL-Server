package com.sql.logic.engine.domain.agent.core;

import com.sql.logic.engine.domain.agent.strategy.LLMStrategy;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.function.BiConsumer;

public interface AiAgent {
    
    /**
     * Set the dynamic schema context provider for this agent
     */
    void setSchemaContextProvider(SchemaContextProvider provider);

    /**
     * Generates SQL using the internal LLM Strategy, injecting knowledge context (DDL) automatically.
     */
    Flux<String> generateSqlStream(String userInput, Long connectionId, List<String> tableNames, String manualContext, BiConsumer<Integer, String> tokenAndSqlCallback);
    
    /**
     * Get the underlying strategy if needed
     */
    LLMStrategy getLlmStrategy();
    
    interface SchemaContextProvider {
        String buildDynamicSchemaContext(Long connectionId, List<String> tableNames);
    }
}