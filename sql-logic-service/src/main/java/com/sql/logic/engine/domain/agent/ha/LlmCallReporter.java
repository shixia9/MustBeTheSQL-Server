package com.sql.logic.engine.domain.agent.ha;

/**
 * Reports the result of a single LLM call to metrics aggregation and
 * circuit breaker state tracking. Implemented by {@link com.sql.logic.engine.domain.agent.ha.DefaultLlmCallReporter}.
 *
 * <p>Design follows the API-Premium-Gateway "select-then-report" pattern:
 * before the call: selectBestInstance → after the call: reportCallResult.
 */
public interface LlmCallReporter {

    /** Report a completed (successful or failed) LLM call. */
    void report(Long configId, Long userId, boolean success, long latencyMs, int inputTokens, int outputTokens);
}