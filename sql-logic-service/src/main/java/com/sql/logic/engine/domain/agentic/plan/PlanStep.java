package com.sql.logic.engine.domain.agentic.plan;

import java.time.LocalDateTime;

/**
 * A single step in an Agent execution plan.
 */
public class PlanStep {

    private int serialNumber;
    private String agent;
    private String content;
    private String rely;
    private PlanStatus status = PlanStatus.TODO;
    private String result;
    private int retryTimes = 0;
    private int maxRetryTimes = 3;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public PlanStep() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    public PlanStep(int serialNumber, String agent, String content, String rely) {
        this();
        this.serialNumber = serialNumber;
        this.agent = agent;
        this.content = content;
        this.rely = rely;
    }

    // --- Getters / Setters ---

    public int getSerialNumber() { return serialNumber; }
    public void setSerialNumber(int v) { this.serialNumber = v; this.updatedAt = LocalDateTime.now(); }

    public String getAgent() { return agent; }
    public void setAgent(String v) { this.agent = v; this.updatedAt = LocalDateTime.now(); }

    public String getContent() { return content; }
    public void setContent(String v) { this.content = v; this.updatedAt = LocalDateTime.now(); }

    public String getRely() { return rely; }
    public void setRely(String v) { this.rely = v; this.updatedAt = LocalDateTime.now(); }

    public PlanStatus getStatus() { return status; }
    public void setStatus(PlanStatus v) { this.status = v; this.updatedAt = LocalDateTime.now(); }

    public String getResult() { return result; }
    public void setResult(String v) { this.result = v; this.updatedAt = LocalDateTime.now(); }

    public int getRetryTimes() { return retryTimes; }
    public void setRetryTimes(int v) { this.retryTimes = v; this.updatedAt = LocalDateTime.now(); }

    public int getMaxRetryTimes() { return maxRetryTimes; }
    public void setMaxRetryTimes(int v) { this.maxRetryTimes = v; this.updatedAt = LocalDateTime.now(); }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    @Override
    public String toString() {
        return "PlanStep{#" + serialNumber + " " + agent + ": " + content
                + " [" + status + "] rely=" + rely + "}";
    }
}
