package com.sql.logic.engine.domain.agent.ha;

import com.sql.logic.engine.domain.agent.core.LlmClientManager;
import com.sql.logic.engine.domain.agent.strategy.LLMStrategy;
import com.sql.logic.engine.domain.trace.TraceContext;
import com.sql.logic.engine.domain.trace.TracingLlmClientWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.function.BiConsumer;

/**
 * Runtime failover strategy — iterates an ordered candidate chain and tries
 * each candidate in turn until one succeeds. When a candidate throws, the
 * failure is reported (via {@link TracingLlmClientWrapper}) to the
 * {@link com.sql.logic.engine.domain.agent.ha.circuit.CircuitBreaker} so the
 * broken instance can be skipped on subsequent requests.
 *
 * <p>This closes the gap left by {@code LlmClientManager.resolveWithStrategy()},
 * which previously committed to a single strategy <em>before</em> the call and
 * had no way to fall back when that single instance failed at runtime.
 *
 * <p>Behavioural notes:
 * <ul>
 *   <li>Any exception from a candidate triggers fallback — no error-class
 *       filtering. This keeps the logic simple and reliable; the cost of
 *       trying a healthy fallback is negligible compared to waiting on
 *       retries against a broken endpoint.</li>
 *   <li>The same candidate is never retried within a single call —
 *       Spring AI's own {@code RetryTemplate} is expected to be disabled
 *       ({@code spring.ai.retry.max-attempts=1}) so that DNS/connect errors
 *       surface immediately.</li>
 *   <li>Every attempt is wrapped in a fresh {@link TracingLlmClientWrapper}
 *       so token tracing and circuit-breaker reporting fire per candidate.</li>
 * </ul>
 */
public class FailoverLLMStrategy implements LLMStrategy {

    private static final Logger log = LoggerFactory.getLogger(FailoverLLMStrategy.class);

    private final List<Long> candidateConfigIds;
    private final LlmClientManager llmClientManager;
    private final TraceContext traceContext;
    private final String nodeName;
    private final LlmCallReporter reporter;
    private final Long userId;

    public FailoverLLMStrategy(List<Long> candidateConfigIds,
                                LlmClientManager llmClientManager,
                                TraceContext traceContext,
                                String nodeName,
                                LlmCallReporter reporter,
                                Long userId) {
        this.candidateConfigIds = candidateConfigIds;
        this.llmClientManager = llmClientManager;
        this.traceContext = traceContext;
        this.nodeName = nodeName;
        this.reporter = reporter;
        this.userId = userId;
    }

    @Override
    public String generateSql(String prompt, BiConsumer<Integer, String> tokenAndSqlCallback) {
        Exception lastError = null;
        for (int i = 0; i < candidateConfigIds.size(); i++) {
            Long configId = candidateConfigIds.get(i);
            LLMStrategy raw = llmClientManager.getClient(configId);
            if (raw == null) {
                log.warn("[FailoverLLMStrategy] candidate configId={} not registered, skipping (node={})",
                        configId, nodeName);
                continue;
            }
            LLMStrategy traced = new TracingLlmClientWrapper(raw, traceContext, nodeName, reporter,
                    configId, userId);
            try {
                String result = traced.generateSql(prompt, tokenAndSqlCallback);
                if (i > 0) {
                    log.info("[FailoverLLMStrategy] succeeded on fallback configId={} after {} failed attempt(s) (node={})",
                            configId, i, nodeName);
                }
                return result;
            } catch (Exception e) {
                lastError = e;
                boolean hasMore = i < candidateConfigIds.size() - 1;
                if (hasMore) {
                    log.warn("[FailoverLLMStrategy] configId={} failed (node={}): {} — falling back to next candidate",
                            configId, nodeName, e.getMessage());
                } else {
                    log.error("[FailoverLLMStrategy] configId={} failed and no more candidates (node={}): {}",
                            configId, nodeName, e.getMessage());
                }
            }
        }
        throw new IllegalStateException(
                "[FailoverLLMStrategy] All LLM candidates exhausted for node=" + nodeName
                        + ", candidates=" + candidateConfigIds, lastError);
    }

    @Override
    public Flux<String> generateSqlStream(String prompt, BiConsumer<Integer, String> tokenAndSqlCallback) {
        return Flux.defer(() -> tryStreamAt(prompt, tokenAndSqlCallback, 0, null));
    }

    private Flux<String> tryStreamAt(String prompt,
                                      BiConsumer<Integer, String> tokenAndSqlCallback,
                                      int index,
                                      Exception lastError) {
        if (index >= candidateConfigIds.size()) {
            return Flux.error(new IllegalStateException(
                    "[FailoverLLMStrategy] All LLM candidates exhausted for node=" + nodeName
                            + ", candidates=" + candidateConfigIds, lastError));
        }
        Long configId = candidateConfigIds.get(index);
        LLMStrategy raw = llmClientManager.getClient(configId);
        if (raw == null) {
            log.warn("[FailoverLLMStrategy] candidate configId={} not registered, skipping (node={})",
                    configId, nodeName);
            return tryStreamAt(prompt, tokenAndSqlCallback, index + 1, lastError);
        }
        LLMStrategy traced = new TracingLlmClientWrapper(raw, traceContext, nodeName, reporter,
                configId, userId);
        final Exception[] captured = new Exception[1];
        return traced.generateSqlStream(prompt, tokenAndSqlCallback)
                .doOnError(e -> {
                    captured[0] = (e instanceof Exception) ? (Exception) e : new RuntimeException(e);
                    boolean hasMore = index < candidateConfigIds.size() - 1;
                    if (hasMore) {
                        log.warn("[FailoverLLMStrategy] configId={} stream failed (node={}): {} — falling back to next candidate",
                                configId, nodeName, e.getMessage());
                    } else {
                        log.error("[FailoverLLMStrategy] configId={} stream failed and no more candidates (node={}): {}",
                                configId, nodeName, e.getMessage());
                    }
                })
                .onErrorResume(e -> {
                    Exception err = captured[0] != null ? captured[0]
                            : (e instanceof Exception ? (Exception) e : new RuntimeException(e));
                    int next = index + 1;
                    if (next > 0 && next < candidateConfigIds.size()) {
                        log.info("[FailoverLLMStrategy] falling back from configId={} to configId={} (node={})",
                                configId, candidateConfigIds.get(next), nodeName);
                    }
                    return tryStreamAt(prompt, tokenAndSqlCallback, next, err);
                });
    }
}