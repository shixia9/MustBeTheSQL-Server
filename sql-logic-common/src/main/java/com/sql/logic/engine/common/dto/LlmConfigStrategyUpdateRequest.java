package com.sql.logic.engine.common.dto;

import java.util.List;

public class LlmConfigStrategyUpdateRequest {
    private String strategyType;
    private List<Long> fallbackChain;

    public String getStrategyType() { return strategyType; }
    public void setStrategyType(String strategyType) { this.strategyType = strategyType; }
    public List<Long> getFallbackChain() { return fallbackChain; }
    public void setFallbackChain(List<Long> fallbackChain) { this.fallbackChain = fallbackChain; }
}
