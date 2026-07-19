package com.sql.logic.engine.domain.agent.strategy;

import reactor.core.publisher.Flux;

import java.util.function.BiConsumer;

public interface LLMStrategy {

    // ======================== SQL Generation ========================

    /**
     * Stream-generated SQL (default NL_TO_SQL prompt type)
     * @param prompt the prompt
     * @param tokenAndSqlCallback callback for token consumption and SQL content
     * @return stream of generated SQL events
     */
    Flux<String> generateSqlStream(String prompt, BiConsumer<Integer, String> tokenAndSqlCallback);

    /**
     * Stream-generated SQL with specified prompt type
     * @param prompt the prompt
     * @param promptType the type of AI operation (NL_TO_SQL, SQL_EXPLAIN, etc.)
     * @param tokenAndSqlCallback callback for token consumption and SQL content
     * @return stream of generated events
     */
    default Flux<String> generateSqlStream(String prompt, PromptType promptType, BiConsumer<Integer, String> tokenAndSqlCallback) {
        // Default implementation delegates to the base method
        // Subclasses can override to customize behavior per prompt type
        return generateSqlStream(prompt, tokenAndSqlCallback);
    }

    /**
     * Non-streaming SQL generation (default NL_TO_SQL prompt type)
     * @param prompt the prompt
     * @param tokenAndSqlCallback callback for token consumption and SQL content
     * @return generated SQL content
     */
    String generateSql(String prompt, BiConsumer<Integer, String> tokenAndSqlCallback);

    /**
     * Non-streaming SQL generation with specified prompt type
     * @param prompt the prompt
     * @param promptType the type of AI operation
     * @param tokenAndSqlCallback callback for token consumption and SQL content
     * @return generated content
     */
    default String generateSql(String prompt, PromptType promptType, BiConsumer<Integer, String> tokenAndSqlCallback) {
        return generateSql(prompt, tokenAndSqlCallback);
    }

    // ======================== General-purpose Chat ========================

    /**
     * General-purpose non-streaming LLM call for internal agent operations.
     * @param prompt the raw prompt to send to the LLM
     * @return the LLM's text response
     */
    default String chat(String prompt) {
        return generateSql(prompt, (BiConsumer<Integer, String>) null);
    }
}
