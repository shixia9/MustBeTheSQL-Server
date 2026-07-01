package com.sql.logic.engine.domain.agent.core;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.sql.logic.engine.domain.trace.TraceContext;
import com.sql.logic.engine.infrastructure.po.AgentExecutionStep;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-run context retained between the first graph execution (which pauses before the
 * HITL interrupt node) and the subsequent {@code /confirm} resume call.
 * <p>
 * Stored in {@link HitlSessionRegistry} keyed by threadId. Carries the user/connection/
 * LLM resolution inputs (needed to authorise resume and re-resolve constraints) plus
 * the {@link RunnableConfig} that owns the suspended checkpoint.
 * <p>
 * Also accumulates the per-node {@link AgentExecutionStep} records as the graph
 * completes each node. This list is shared across the initial run and any resumed
 * continuation (the same context instance is reused), so the consolidated history
 * reflects the full session end-to-end.
 */
public class AgentRunContext {

    private final String threadId;
    private final Long userId;
    private final Long connectionId;
    private final Long llmConfigId;
    private final Long workspaceId;
    private final String schemaName;
    private final List<String> tableNames;
    private final boolean autoConfirm;
    private final RunnableConfig runnableConfig;
    private final long createdAt;

    /** Ordered per-node step records accumulated during streaming; drain on persist. */
    private final ConcurrentLinkedQueue<AgentExecutionStep> stepBuffer = new ConcurrentLinkedQueue<>();
    /** Monotonic sequence number for steps across the (possibly resumed) run. */
    private final AtomicInteger nextSequence = new AtomicInteger(1);
    /** trace carrier — accumulates timing/token data per node. Nullable for backward compat. */
    private TraceContext traceContext;

    public AgentRunContext(String threadId, Long userId, Long connectionId, Long llmConfigId,
                           Long workspaceId, List<String> tableNames, String schemaName, boolean autoConfirm, RunnableConfig runnableConfig) {
        this.threadId = threadId;
        this.userId = userId;
        this.connectionId = connectionId;
        this.llmConfigId = llmConfigId;
        this.workspaceId = workspaceId;
        this.tableNames = tableNames;
        this.schemaName = schemaName;
        this.autoConfirm = autoConfirm;
        this.runnableConfig = runnableConfig;
        this.createdAt = System.currentTimeMillis();
    }

    public String getThreadId() { return threadId; }
    public Long getUserId() { return userId; }
    public Long getConnectionId() { return connectionId; }
    public Long getLlmConfigId() { return llmConfigId; }
    public Long getWorkspaceId() { return workspaceId; }
    public String getSchemaName() { return schemaName; }
    public List<String> getTableNames() { return tableNames; }
    public boolean isAutoConfirm() { return autoConfirm; }
    public RunnableConfig getRunnableConfig() { return runnableConfig; }
    public long getCreatedAt() { return createdAt; }

    public TraceContext getTraceContext() { return traceContext; }
    public void setTraceContext(TraceContext traceContext) { this.traceContext = traceContext; }

    /**
     * Append a per-node step record to the in-memory buffer and return the assigned
     * sequence number. Thread-safe; called from the reactive stream's map operator.
     */
    public int appendStep(String nodeName, String status, Long durationMs, String outputDataJson) {
        AgentExecutionStep step = new AgentExecutionStep();
        step.setNodeName(nodeName);
        step.setStatus(status);
        step.setDurationMs(durationMs);
        step.setOutputData(outputDataJson);
        step.setSequenceNo(nextSequence.getAndIncrement());
        stepBuffer.add(step);
        return step.getSequenceNo();
    }

    /** Drain and return all buffered step records (for persistence). */
    public List<AgentExecutionStep> drainSteps() {
        List<AgentExecutionStep> drained = new java.util.ArrayList<>(stepBuffer.size());
        AgentExecutionStep s;
        while ((s = stepBuffer.poll()) != null) {
            drained.add(s);
        }
        return drained;
    }
}