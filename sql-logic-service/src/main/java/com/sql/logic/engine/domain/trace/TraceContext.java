package com.sql.logic.engine.domain.trace;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
    /** Ordered list of per-node-visit trace entries. Appended by beginNode, never removed. */
    private final List<StepTrace> steps;
    /** Index into {@link #steps} of the node currently executing (-1 when idle). */
    private volatile int currentNodeIndex = -1;
    private volatile long lastStepTimeMs;

    public TraceContext(String threadId, Long userId, Long workspaceId) {
        this.threadId = threadId;
        this.userId = userId;
        this.workspaceId = workspaceId;
        this.startTime = System.currentTimeMillis();
        this.lastStepTimeMs = this.startTime;
        this.modelCalls = new AtomicInteger(0);
        this.totalInputTokens = new AtomicInteger(0);
        this.totalOutputTokens = new AtomicInteger(0);
        this.steps = Collections.synchronizedList(new ArrayList<>());
    }

    /** Mark a node as started — recorded by the graph lifecycle listener's before().
     *  Appends a fresh StepTrace so looped re-entries to the same node are distinct. */
    public void beginNode(String nodeName) {
        StepTrace st = new StepTrace();
        st.nodeName = nodeName;
        st.nodeStartMs = System.currentTimeMillis();
        steps.add(st);
        currentNodeIndex = steps.size() - 1;
    }

    /** Mark the current node as finished and compute its real wall-clock duration. */
    public void endNode(String nodeName) {
        int idx = this.currentNodeIndex;
        if (idx >= 0 && idx < steps.size()) {
            StepTrace st = steps.get(idx);
            if (st.durationMs == 0 && st.nodeStartMs > 0) {
                st.durationMs = System.currentTimeMillis() - st.nodeStartMs;
            }
        }
        this.currentNodeIndex = -1;
    }

    /** Add tokens to the global totals (always). */
    public void addTokens(int input, int output) {
        totalInputTokens.addAndGet(input);
        totalOutputTokens.addAndGet(output);
    }

    /** Attribute an LLM call's tokens to whatever node is currently executing. */
    public void addTokensToCurrentNode(int input, int output) {
        int idx = this.currentNodeIndex;
        if (idx < 0 || idx >= steps.size()) return;
        StepTrace st = steps.get(idx);
        if (st != null) {
            st.inputTokens += input;
            st.outputTokens += output;
        }
    }

    public void incrementModelCalls() { modelCalls.incrementAndGet(); }

    /** Record a completed step with explicit latency. Does NOT touch token
     *  fields — those are owned by addTokensToCurrentNode. Also calls
     *  endNode to finalize durationMs. */
    public void recordStep(int sequence, String nodeName, String status, int inputTokens, int outputTokens, long latencyMs, String nodeType) {
        int idx = this.currentNodeIndex;
        if (idx >= 0 && idx < steps.size()) {
            StepTrace st = steps.get(idx);
            st.fill(sequence, nodeName, status, latencyMs, nodeType);
        }
        endNode(nodeName);
    }

    /** Record a step with auto-computed wall-clock latency. */
    public void recordStep(int sequence, String nodeName, String status, int inputTokens, int outputTokens, String nodeType) {
        long now = System.currentTimeMillis();
        long latency = now - lastStepTimeMs;
        lastStepTimeMs = now;
        recordStep(sequence, nodeName, status, inputTokens, outputTokens, latency, nodeType);
    }

    /** Mark the last step time to a specific value (e.g., to reset after a pause). */
    public void touchLastStepTime() {
        this.lastStepTimeMs = System.currentTimeMillis();
    }

    // Getters
    public String getThreadId() { return threadId; }
    public Long getUserId() { return userId; }
    public Long getWorkspaceId() { return workspaceId; }
    public long getStartTime() { return startTime; }
    public int getModelCalls() { return modelCalls.get(); }
    public int getTotalInputTokens() { return totalInputTokens.get(); }
    public int getTotalOutputTokens() { return totalOutputTokens.get(); }
    /** Return an indexed snapshot of all step traces, ordered by node visit. */
    public List<StepTrace> getSteps() {
        synchronized (steps) {
            return new ArrayList<>(steps);
        }
    }

    /** Inner step trace record. */
    public static class StepTrace {
        public int sequence;
        public String nodeName;
        public String status;
        public int inputTokens;
        public int outputTokens;
        public long latencyMs;
        /** Real node execution duration (begin→end). Populated by endNode. */
        public long durationMs;
        public String nodeType;
        /** Set by beginNode so endNode can compute durationMs. */
        public long nodeStartMs;

        void fill(int seq, String name, String st, long lat, String type) {
            this.sequence = seq;
            this.nodeName = name;
            this.status = st;
            this.latencyMs = lat;
            this.nodeType = type;
            if (this.durationMs == 0 && this.nodeStartMs > 0) {
                this.durationMs = System.currentTimeMillis() - this.nodeStartMs;
            }
        }
    }
}