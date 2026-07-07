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
    /** key = nodeName (looped nodes may use "name:step"). */
    private final ConcurrentMap<String, StepTrace> steps;
    private volatile long lastStepTimeMs;

    /** The node currently being executed (set by beginNode, cleared by endNode).
     *  Used by addTokensToCurrentNode to attribute LLM token usage. */
    private volatile String currentNodeKey;

    public TraceContext(String threadId, Long userId, Long workspaceId) {
        this.threadId = threadId;
        this.userId = userId;
        this.workspaceId = workspaceId;
        this.startTime = System.currentTimeMillis();
        this.lastStepTimeMs = this.startTime;
        this.modelCalls = new AtomicInteger(0);
        this.totalInputTokens = new AtomicInteger(0);
        this.totalOutputTokens = new AtomicInteger(0);
        this.steps = new ConcurrentHashMap<>();
    }

    /** Mark a node as started — recorded by the graph lifecycle listener's before().
     *  Must be called before the node's LLM call so reportCall can attribute tokens. */
    public void beginNode(String nodeName) {
        String key = stepKey(nodeName);
        this.currentNodeKey = key;
        StepTrace st = steps.computeIfAbsent(key, k -> new StepTrace());
        if (st.nodeStartMs == 0) {
            st.nodeStartMs = System.currentTimeMillis();
        }
    }

    /** Mark a node as finished and compute its real wall-clock duration. */
    public void endNode(String nodeName) {
        String key = stepKey(nodeName);
        StepTrace st = steps.get(key);
        if (st != null && st.nodeStartMs > 0 && st.durationMs == 0) {
            st.durationMs = System.currentTimeMillis() - st.nodeStartMs;
        }
        if (key.equals(this.currentNodeKey)) {
            this.currentNodeKey = null;
        }
    }

    /** Add tokens to the global totals (always). */
    public void addTokens(int input, int output) {
        totalInputTokens.addAndGet(input);
        totalOutputTokens.addAndGet(output);
    }

    /** Attribute an LLM call's tokens to whatever node is currently executing
     *  (set by beginNode). No-op if no node is active. */
    public void addTokensToCurrentNode(int input, int output) {
        String key = this.currentNodeKey;
        if (key == null) return;
        StepTrace st = steps.get(key);
        if (st != null) {
            st.inputTokens += input;
            st.outputTokens += output;
        }
    }

    public void incrementModelCalls() { modelCalls.incrementAndGet(); }

    /** Record a completed step with explicit latency. <b>Does NOT touch token
     *  fields</b> — those are owned by addTokensToCurrentNode. Also calls
     *  endNode to finalize durationMs. */
    public void recordStep(int sequence, String nodeName, String status, int inputTokens, int outputTokens, long latencyMs, String nodeType) {
        String key = stepKey(nodeName);
        StepTrace st = steps.computeIfAbsent(key, k -> new StepTrace());
        st.fill(sequence, nodeName, status, latencyMs, nodeType);
        endNode(key);
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

    private String stepKey(String nodeName) {
        return nodeName == null ? "" : nodeName;
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

    /** Inner step trace record. Token fields are owned by addTokensToCurrentNode;
     *  fill() deliberately does NOT reset them. */
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