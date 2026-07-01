package com.sql.logic.engine.domain.trace;

/**
 * Decorator that wraps an LLMStrategy to capture token usage during streaming.
 * DESIGNED for Phase A; IMPLEMENTED in Phase B.
 *
 * When Phase B enables this, each LLM-calling node will wrap its LLMStrategy
 * through this decorator, and token counts will automatically flow into
 * TraceContext without changing node internals.
 *
 * <pre>
 * LLMStrategy traced = new TracingLlmClientWrapper(rawStrategy, traceContext);
 * String result = traced.generateSqlStream(prompt, callback);
 * // traceContext now has accurate input/output token counts
 * </pre>
 */
/*
public class TracingLlmClientWrapper implements LLMStrategy {
    private final LLMStrategy delegate;
    private final TraceContext traceContext;
    // ...
}
*/
public class TracingLlmClientWrapper {
    private TracingLlmClientWrapper() { /* prevent instantiation — Phase B */ }
}
