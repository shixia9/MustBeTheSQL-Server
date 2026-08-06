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

    public BusOrchestrationMode getMode() { return mode; }
    public void setMode(BusOrchestrationMode mode) {
        this.mode = mode != null ? mode : BusOrchestrationMode.OFF;
    }

    public long getDispatcherTimeoutSeconds() { return dispatcherTimeoutSeconds; }
    public void setDispatcherTimeoutSeconds(long dispatcherTimeoutSeconds) {
        this.dispatcherTimeoutSeconds = dispatcherTimeoutSeconds;
    }
}
