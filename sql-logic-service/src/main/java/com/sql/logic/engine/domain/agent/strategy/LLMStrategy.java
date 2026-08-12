package com.sql.logic.engine.domain.agent.strategy;

import reactor.core.publisher.Flux;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

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

    /**
     * Non-streaming LLM call with native thinking mode enabled.
     *
     * <p>When the underlying LLM supports thinking (e.g., Doubao, DeepSeek),
     * the API returns {@code reasoning_content} alongside {@code content} in
     * a single call — no extra LLM round-trip is needed. The reasoning is
     * genuine chain-of-thought produced by the model itself, not a separate
     * prompt.
     *
     * <p>The default implementation falls back to {@link #chat(String)} with
     * empty reasoning. Implementations that support thinking mode (e.g.,
     * {@code OpenAILLMStrategy} with a configured RestClient) should override
     * this to make a direct API call with {@code thinking: {type: "enabled"}}.
     *
     * @param prompt the raw prompt to send to the LLM
     * @return {@link ThinkingResult} containing both reasoning and content
     */
    default ThinkingResult chatWithThinking(String prompt) {
        return new ThinkingResult(chat(prompt), "");
    }

    /**
     * Streaming LLM call with native thinking mode enabled.
     *
     * <p>Similar to {@link #chatWithThinking(String)}, but the API call uses
     * {@code stream: true} so reasoning chunks arrive incrementally via the
     * {@code onReasoningChunk} callback.
     *
     * <p>The default implementation falls back to {@link #chatWithThinking(String)}
     * and emits the full reasoning as a single chunk. Implementations with
     * streaming support (e.g., {@code OpenAILLMStrategy}) should override this
     * to parse SSE chunks and invoke the callback per delta.
     *
     * @param prompt          the raw prompt to send to the LLM
     * @param onReasoningChunk callback invoked for each reasoning text delta
     * @return {@link ThinkingResult} with the full reasoning and content
     */
    default ThinkingResult chatWithThinkingStream(String prompt, Consumer<String> onReasoningChunk) {
        ThinkingResult result = chatWithThinking(prompt);
        if (!result.reasoning().isEmpty()) {
            onReasoningChunk.accept(result.reasoning());
        }
        return result;
    }
}
