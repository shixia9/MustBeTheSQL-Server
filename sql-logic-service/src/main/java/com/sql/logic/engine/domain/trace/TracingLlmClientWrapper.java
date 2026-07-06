package com.sql.logic.engine.domain.trace;

import com.sql.logic.engine.domain.agent.ha.LlmCallReporter;
import com.sql.logic.engine.domain.agent.strategy.LLMStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.function.BiConsumer;

/**
 * Decorator that wraps an LLMStrategy to capture real token usage,
 * latency, and model-call count into a {@link TraceContext}.
 * <p>
 * Every LLM-calling node resolves its strategy through
 * {@link com.sql.logic.engine.domain.agent.core.LlmClientManager#resolveTraced}
 * so tracing is transparent — no node internals are changed beyond the
 * strategy resolution line.
 * <p>
 * Token allocation: the upstream callback from each concrete strategy
 * (OpenAILLMStrategy, AnthropicLLMStrategy) currently delivers only
 * {@code totalTokens} via {@link org.springframework.ai.chat.metadata.Usage#getTotalTokens()}.
 * We approximate input/output as total/2 each. When {@code getPromptTokens}
 * and {@code getCompletionTokens} become available at the strategy level,
 * this wrapper can be updated to split accurately.
 */
public class TracingLlmClientWrapper implements LLMStrategy {

    private static final Logger log = LoggerFactory.getLogger(TracingLlmClientWrapper.class);

    private final LLMStrategy delegate;
    private final TraceContext traceContext;
    private final String nodeName;
    private final LlmCallReporter reporter;
    private final Long configId;
    private final Long userId;

    public TracingLlmClientWrapper(LLMStrategy delegate, TraceContext traceContext,
                                   String nodeName, LlmCallReporter reporter,
                                   Long configId, Long userId) {
        this.delegate = delegate;
        this.traceContext = traceContext;
        this.nodeName = nodeName;
        this.reporter = reporter;
        this.configId = configId;
        this.userId = userId;
    }

    @Override
    public Flux<String> generateSqlStream(String prompt, BiConsumer<Integer, String> tokenAndSqlCallback) {
        long start = System.currentTimeMillis();
        return delegate.generateSqlStream(prompt, (tokens, sql) -> {
            reportCall(tokens, true, System.currentTimeMillis() - start);
            if (tokenAndSqlCallback != null) {
                tokenAndSqlCallback.accept(tokens, sql);
            }
        }).doOnError(e -> {
            reportCall(0, false, System.currentTimeMillis() - start);
        });
    }

    @Override
    public String generateSql(String prompt, BiConsumer<Integer, String> tokenAndSqlCallback) {
        long start = System.currentTimeMillis();
        try {
            String result = delegate.generateSql(prompt, (tokens, sql) -> {
                reportCall(tokens, true, System.currentTimeMillis() - start);
                if (tokenAndSqlCallback != null) {
                    tokenAndSqlCallback.accept(tokens, sql);
                }
            });
            return result;
        } catch (Exception e) {
            reportCall(0, false, System.currentTimeMillis() - start);
            throw e;
        }
    }

    /**
     * Record the call result into TraceContext and (optionally) the reporter.
     * <p>
     * Input/output tokens are approximated from total: each is total/2.
     * This keeps TraceContext totals consistent: in + out == total.
     */
    private void reportCall(int totalTokens, boolean success, long latencyMs) {
        try {
            int half = totalTokens / 2;
            traceContext.addTokens(half, totalTokens - half);
            traceContext.incrementModelCalls();
            if (reporter != null) {
                reporter.report(configId, userId, success, latencyMs, half, totalTokens - half);
            }
        } catch (Exception e) {
            log.warn("[TracingLlmClientWrapper] reportCall error (node={}): {}", nodeName, e.getMessage());
        }
    }
}
