package com.sql.logic.engine.common.dto;

public class LlmConfigMetricsResponse {
    private Long configId;
    private double successRate;
    private long averageLatencyMs;
    private String circuitState;
    private int totalRequests;

    public LlmConfigMetricsResponse() {}

    public LlmConfigMetricsResponse(Long configId, double successRate, long averageLatencyMs,
                                     String circuitState, int totalRequests) {
        this.configId = configId;
        this.successRate = successRate;
        this.averageLatencyMs = averageLatencyMs;
        this.circuitState = circuitState;
        this.totalRequests = totalRequests;
    }

    public Long getConfigId() { return configId; }
    public void setConfigId(Long configId) { this.configId = configId; }
    public double getSuccessRate() { return successRate; }
    public void setSuccessRate(double successRate) { this.successRate = successRate; }
    public long getAverageLatencyMs() { return averageLatencyMs; }
    public void setAverageLatencyMs(long averageLatencyMs) { this.averageLatencyMs = averageLatencyMs; }
    public String getCircuitState() { return circuitState; }
    public void setCircuitState(String circuitState) { this.circuitState = circuitState; }
    public int getTotalRequests() { return totalRequests; }
    public void setTotalRequests(int totalRequests) { this.totalRequests = totalRequests; }
}
