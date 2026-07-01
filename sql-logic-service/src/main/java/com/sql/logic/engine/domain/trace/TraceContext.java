package com.sql.logic.engine.domain.trace;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-execution trace carrier. Captured at request start and referenced
 * throughout node execution to accumulate timing and token data.
 * Propagated via AgentRunContext, NOT ThreadLocal (Reactor threading incompatibility).
 */
public class TraceContext {
    private final String threadId;
    private final Long userId;
    private final Long workspaceId;
    private final long startTime;
    private final AtomicInteger modelCalls;
    private final AtomicInteger totalInputTokens;
    private final AtomicInteger totalOutputTokens;
    private final ConcurrentMap<String, StepTrace> steps;  // key=sequence:nodeName

    public TraceContext(String threadId, Long userId, Long workspaceId) {
        this.threadId = threadId;
        this.userId = userId;
        this.workspaceId = workspaceId;
        this.startTime = System.currentTimeMillis();
        this.modelCalls = new AtomicInteger(0);
        this.totalInputTokens = new AtomicInteger(0);
        this.totalOutputTokens = new AtomicInteger(0);
        this.steps = new ConcurrentHashMap<>();
    }

    public void addTokens(int input, int output) {
        totalInputTokens.addAndGet(input);
        totalOutputTokens.addAndGet(output);
    }

    public void incrementModelCalls() { modelCalls.incrementAndGet(); }

    public void recordStep(int sequence, String nodeName, String status, int inputTokens, int outputTokens, long latencyMs, String nodeType) {
        String key = sequence + ":" + nodeName;
        steps.computeIfAbsent(key, k -> new StepTrace()).fill(sequence, nodeName, status, inputTokens, outputTokens, latencyMs, nodeType);
    }

    // Getters
    public String getThreadId() { return threadId; }
    public Long getUserId() { return userId; }
    public Long getWorkspaceId() { return workspaceId; }
    public long getStartTime() { return startTime; }
    public int getModelCalls() { return modelCalls.get(); }
    public int getTotalInputTokens() { return totalInputTokens.get(); }
    public int getTotalOutputTokens() { return totalOutputTokens.get(); }
    public ConcurrentMap<String, StepTrace> getSteps() { return steps; }

    /** Inner step trace record */
    public static class StepTrace {
        public int sequence;
        public String nodeName;
        public String status;
        public int inputTokens;
        public int outputTokens;
        public long latencyMs;
        public String nodeType;

        void fill(int seq, String name, String st, int inTok, int outTok, long lat, String type) {
            this.sequence = seq;
            this.nodeName = name;
            this.status = st;
            this.inputTokens = inTok;
            this.outputTokens = outTok;
            this.latencyMs = lat;
            this.nodeType = type;
        }
    }
}
