package com.sql.logic.engine.domain.agent.core;

import com.alibaba.cloud.ai.graph.RunnableConfig;

import java.util.List;

/**
 * Per-run context retained between the first graph execution (which pauses before the
 * HITL interrupt node) and the subsequent {@code /confirm} resume call.
 * <p>
 * Stored in {@link HitlSessionRegistry} keyed by threadId. Carries the user/connection/
 * LLM resolution inputs (needed to authorise resume and re-resolve constraints) plus
 * the {@link RunnableConfig} that owns the suspended checkpoint.
 */
public class AgentRunContext {

    private final String threadId;
    private final Long userId;
    private final Long connectionId;
    private final Long llmConfigId;
    private final List<String> tableNames;
    private final boolean autoConfirm;
    private final RunnableConfig runnableConfig;
    private final long createdAt;

    public AgentRunContext(String threadId, Long userId, Long connectionId, Long llmConfigId,
                           List<String> tableNames, boolean autoConfirm, RunnableConfig runnableConfig) {
        this.threadId = threadId;
        this.userId = userId;
        this.connectionId = connectionId;
        this.llmConfigId = llmConfigId;
        this.tableNames = tableNames;
        this.autoConfirm = autoConfirm;
        this.runnableConfig = runnableConfig;
        this.createdAt = System.currentTimeMillis();
    }

    public String getThreadId() { return threadId; }
    public Long getUserId() { return userId; }
    public Long getConnectionId() { return connectionId; }
    public Long getLlmConfigId() { return llmConfigId; }
    public List<String> getTableNames() { return tableNames; }
    public boolean isAutoConfirm() { return autoConfirm; }
    public RunnableConfig getRunnableConfig() { return runnableConfig; }
    public long getCreatedAt() { return createdAt; }
}