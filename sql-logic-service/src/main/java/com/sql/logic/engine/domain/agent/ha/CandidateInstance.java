package com.sql.logic.engine.domain.agent.ha;

import com.sql.logic.engine.domain.agent.strategy.LLMStrategy;

public class CandidateInstance {

    private final Long configId;
    private final LLMStrategy strategy;
    private final double successRate;
    private final long averageLatencyMs;
    private final int consecutiveFailures;
    private final boolean circuitBreakerOpen;

    public CandidateInstance(Long configId, LLMStrategy strategy, double successRate,
                              long averageLatencyMs, int consecutiveFailures, boolean circuitBreakerOpen) {
        this.configId = configId;
        this.strategy = strategy;
        this.successRate = successRate;
        this.averageLatencyMs = averageLatencyMs;
        this.consecutiveFailures = consecutiveFailures;
        this.circuitBreakerOpen = circuitBreakerOpen;
    }

    public Long getConfigId() { return configId; }
    public LLMStrategy getStrategy() { return strategy; }
    public double getSuccessRate() { return successRate; }
    public long getAverageLatencyMs() { return averageLatencyMs; }
    public int getConsecutiveFailures() { return consecutiveFailures; }
    public boolean isCircuitBreakerOpen() { return circuitBreakerOpen; }
}
