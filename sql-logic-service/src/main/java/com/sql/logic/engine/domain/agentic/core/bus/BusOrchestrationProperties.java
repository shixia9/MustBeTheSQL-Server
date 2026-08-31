package com.sql.logic.engine.domain.agentic.core.bus;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Operator-tunable knobs for the message-bus ↔ AgentOrchestrator integration,
 * bound from {@code bus-orc.*} properties.
 */
@Component
@ConfigurationProperties(prefix = "bus-orc")
public class BusOrchestrationProperties {

    /**
     * Integration mode.
     */
    private BusOrchestrationMode mode = BusOrchestrationMode.OFF;

    /**
     * Maximum seconds to await a worker's {@code ToolResult} reply in
     * {@link BusOrchestrationMode#SWITCH} mode before failing the dispatch.
     * Generous default to accommodate long-running LLM + sandbox steps.
     */
    private long dispatcherTimeoutSeconds = 300L;

    /** Maximum time to wait for worker acceptance before a pre-ACK retry. */
    private long ackTimeoutSeconds = 3L;

    /** Maximum number of sends for one logical task, including the first send. */
    private int maxDispatchAttempts = 3;

    /** Base delay for linear backoff between pre-ACK retries. */
    private long retryBackoffMillis = 100L;

    public BusOrchestrationMode getMode() { return mode; }
    public void setMode(BusOrchestrationMode mode) {
        this.mode = mode != null ? mode : BusOrchestrationMode.OFF;
    }

    public long getDispatcherTimeoutSeconds() { return dispatcherTimeoutSeconds; }
    public void setDispatcherTimeoutSeconds(long dispatcherTimeoutSeconds) {
        this.dispatcherTimeoutSeconds = dispatcherTimeoutSeconds;
    }

    public long getAckTimeoutSeconds() { return ackTimeoutSeconds; }
    public void setAckTimeoutSeconds(long ackTimeoutSeconds) {
        this.ackTimeoutSeconds = ackTimeoutSeconds;
    }

    public int getMaxDispatchAttempts() { return maxDispatchAttempts; }
    public void setMaxDispatchAttempts(int maxDispatchAttempts) {
        this.maxDispatchAttempts = maxDispatchAttempts;
    }

    public long getRetryBackoffMillis() { return retryBackoffMillis; }
    public void setRetryBackoffMillis(long retryBackoffMillis) {
        this.retryBackoffMillis = retryBackoffMillis;
    }
}
