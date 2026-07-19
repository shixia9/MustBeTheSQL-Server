package com.sql.logic.engine.domain.agentic.context;

import com.sql.logic.engine.domain.agentic.core.AgentMessage;
import com.sql.logic.engine.domain.agentic.util.TokenCounter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Tracks token usage and determines context budget state.
 * <p>
 * Uses a {@link TokenCounter} JTokkit-backed or character-estimation fallback
 * to count tokens across AgentMessage lists, then compares against budget thresholds.
 */
public class ContextBudgetTracker {

    private static final Logger log = LoggerFactory.getLogger(ContextBudgetTracker.class);

    private final ContextBudgetConfig config;
    private final TokenCounter tokenCounter;
    private final List<Integer> tokenHistory = new ArrayList<>();
    private int compactFailureCount;

    public ContextBudgetTracker(ContextBudgetConfig config) {
        this(config, new TokenCounter());
    }

    public ContextBudgetTracker(ContextBudgetConfig config, String modelName) {
        this(config, new TokenCounter(modelName));
    }

    public ContextBudgetTracker(ContextBudgetConfig config, TokenCounter tokenCounter) {
        this.config = config;
        this.tokenCounter = tokenCounter;
    }

    /** Count total tokens across a list of AgentMessages. */
    public int countMessages(List<AgentMessage> messages) {
        int total = 0;
        for (AgentMessage msg : messages) {
            String content = msg.content();
            if (content != null && !content.isEmpty()) {
                total += tokenCounter.count(content);
            }
        }
        return total;
    }

    /** Determine budget state based on current token count. */
    public TokenState getState(int tokenCount) {
        int budget = config.effectiveBudget();
        if (budget <= 0) return TokenState.OVERFLOW;

        double ratio = (double) tokenCount / budget;
        if (ratio >= 1.0) return TokenState.OVERFLOW;
        if (ratio >= config.criticalThreshold()) return TokenState.CRITICAL;
        if (ratio >= config.errorThreshold()) return TokenState.ERROR;
        if (ratio >= config.warningThreshold()) return TokenState.WARNING;
        return TokenState.NORMAL;
    }

    public void recordTokenCount(int count) {
        tokenHistory.add(count);
    }

    public void recordCompactSuccess() {
        compactFailureCount = 0;
    }

    public void recordCompactFailure() {
        compactFailureCount++;
        log.warn("Context compaction failed (consecutive failures: {}/{})",
                compactFailureCount, config.maxCompactFailures());
    }

    public boolean isCircuitBreakerTripped() {
        return compactFailureCount >= config.maxCompactFailures();
    }

    public List<Integer> getTokenHistory() {
        return List.copyOf(tokenHistory);
    }

    public ContextBudgetConfig getConfig() { return config; }
    public TokenCounter getTokenCounter() { return tokenCounter; }
}
