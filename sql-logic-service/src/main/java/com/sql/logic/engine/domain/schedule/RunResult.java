package com.sql.logic.engine.domain.schedule;

/**
 * Outcome of a single scheduled-task run, produced by a {@link ScheduledTaskRunner}.
 */
public class RunResult {

    private final String summary;
    private final String outputConversationId;
    private final boolean success;

    public RunResult(String summary, String outputConversationId) {
        this(summary, outputConversationId, true);
    }

    public RunResult(String summary, String outputConversationId, boolean success) {
        this.summary = summary;
        this.outputConversationId = outputConversationId;
        this.success = success;
    }

    public String getSummary() {
        return summary;
    }

    public String getOutputConversationId() {
        return outputConversationId;
    }

    public boolean isSuccess() {
        return success;
    }
}
