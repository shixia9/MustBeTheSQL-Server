package com.sql.logic.engine.domain.agent.core;

import com.sql.logic.engine.domain.agent.strategy.LLMStrategy;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.function.BiConsumer;

/**
 * AI Agent responsible for building prompts and delegating to an LLM strategy.
 *
 * An agent is stateless with respect to which LLM it uses — the LLM strategy
 * is either the system default (baked in at construction) or provided at call time
 * based on the user's selected configuration.
 */
public interface AiAgent {

    void setSchemaContextProvider(SchemaContextProvider provider);

    /**
     * Generate SQL using the agent's default (system) LLM strategy.
     * Used when no specific config is selected.
     */
    Flux<String> generateSqlStream(String userInput, Long connectionId, List<String> tableNames,
                                   String manualContext, BiConsumer<Integer, String> tokenAndSqlCallback);

    /**
     * Generate SQL using a specific LLM strategy (user's chosen config).
     * The strategy is provided at runtime, not baked into the agent.
     */
    Flux<String> generateSqlStream(String userInput, Long connectionId, List<String> tableNames,
                                   String manualContext, BiConsumer<Integer, String> tokenAndSqlCallback,
                                   LLMStrategy strategy);

    /**
     * Get the default (system) LLM strategy for this agent.
     */
    LLMStrategy getLlmStrategy();

    interface SchemaContextProvider {
        String buildDynamicSchemaContext(Long connectionId, List<String> tableNames);
    }
}