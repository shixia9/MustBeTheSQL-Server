package com.sql.logic.engine.domain.agentic.core;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Structured message envelope for inter-Agent communication.
 * Replaces bare {@code Map<String,Object>} state passing with typed fields.
 * Immutable — use {@link #withContent}, {@link #withActionReport}, etc. for modified copies.
 */
public class AgentMessage {

    public enum MessageType {
        SYSTEM, USER, AI, TOOL
    }

    private final String content;
    private final String currentGoal;
    private final String senderName;
    private final String senderRole;
    private final int rounds;
    private final boolean success;
    private final String modelName;
    private final ActionOutput actionReport;
    private final ReviewInfo reviewInfo;
    private final Map<String, Object> context;
    private final Map<String, Object> resourceInfo;
    private final MessageType messageType;

    private AgentMessage(Builder builder) {
        this.content = builder.content;
        this.currentGoal = builder.currentGoal;
        this.senderName = builder.senderName;
        this.senderRole = builder.senderRole;
        this.rounds = builder.rounds;
        this.success = builder.success;
        this.modelName = builder.modelName;
        this.actionReport = builder.actionReport;
        this.reviewInfo = builder.reviewInfo;
        this.context = Collections.unmodifiableMap(new HashMap<>(builder.context));
        this.resourceInfo = Collections.unmodifiableMap(new HashMap<>(builder.resourceInfo));
        this.messageType = builder.messageType;
    }

    // --- Getters ---

    public String content() { return content; }
    public String currentGoal() { return currentGoal; }
    public String senderName() { return senderName; }
    public String senderRole() { return senderRole; }
    public int rounds() { return rounds; }
    public boolean success() { return success; }
    public String modelName() { return modelName; }
    public ActionOutput actionReport() { return actionReport; }
    public ReviewInfo reviewInfo() { return reviewInfo; }
    public Map<String, Object> context() { return context; }
    public Map<String, Object> resourceInfo() { return resourceInfo; }
    public MessageType messageType() { return messageType; }

    // --- Immutable "with" copy methods ---

    public AgentMessage withContent(String newContent) {
        return new Builder(this).content(newContent).build();
    }

    public AgentMessage withActionReport(ActionOutput newReport) {
        return new Builder(this).actionReport(newReport).build();
    }

    public AgentMessage withReviewInfo(ReviewInfo newReview) {
        return new Builder(this).reviewInfo(newReview).build();
    }

    public AgentMessage withSuccess(boolean newSuccess) {
        return new Builder(this).success(newSuccess).build();
    }

    public AgentMessage withContext(String key, Object value) {
        Builder b = new Builder(this);
        b.context.put(key, value);
        return b.build();
    }

    // --- Static factories ---

    public static AgentMessage system(String content) {
        return new Builder().content(content).messageType(MessageType.SYSTEM).build();
    }

    public static AgentMessage user(String content) {
        return new Builder().content(content).messageType(MessageType.USER).build();
    }

    public static AgentMessage ai(String content) {
        return new Builder().content(content).messageType(MessageType.AI).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    // --- Builder ---

    public static class Builder {
        private String content;
        private String currentGoal;
        private String senderName;
        private String senderRole;
        private int rounds;
        private boolean success;
        private String modelName;
        private ActionOutput actionReport;
        private ReviewInfo reviewInfo;
        private Map<String, Object> context = new HashMap<>();
        private Map<String, Object> resourceInfo = new HashMap<>();
        private MessageType messageType = MessageType.AI;

        public Builder() {}

        public Builder(AgentMessage source) {
            this.content = source.content;
            this.currentGoal = source.currentGoal;
            this.senderName = source.senderName;
            this.senderRole = source.senderRole;
            this.rounds = source.rounds;
            this.success = source.success;
            this.modelName = source.modelName;
            this.actionReport = source.actionReport;
            this.reviewInfo = source.reviewInfo;
            this.context = new HashMap<>(source.context);
            this.resourceInfo = new HashMap<>(source.resourceInfo);
            this.messageType = source.messageType;
        }

        public Builder content(String v) { this.content = v; return this; }
        public Builder currentGoal(String v) { this.currentGoal = v; return this; }
        public Builder senderName(String v) { this.senderName = v; return this; }
        public Builder senderRole(String v) { this.senderRole = v; return this; }
        public Builder rounds(int v) { this.rounds = v; return this; }
        public Builder success(boolean v) { this.success = v; return this; }
        public Builder modelName(String v) { this.modelName = v; return this; }
        public Builder actionReport(ActionOutput v) { this.actionReport = v; return this; }
        public Builder reviewInfo(ReviewInfo v) { this.reviewInfo = v; return this; }
        public Builder context(Map<String, Object> v) { this.context = v != null ? new HashMap<>(v) : new HashMap<>(); return this; }
        public Builder resourceInfo(Map<String, Object> v) { this.resourceInfo = v != null ? new HashMap<>(v) : new HashMap<>(); return this; }
        public Builder messageType(MessageType v) { this.messageType = v; return this; }
        public Builder putContext(String key, Object value) { this.context.put(key, value); return this; }
        public Builder putResourceInfo(String key, Object value) { this.resourceInfo.put(key, value); return this; }

        public AgentMessage build() {
            return new AgentMessage(this);
        }
    }

    @Override
    public String toString() {
        return "AgentMessage{sender=" + senderName + "(" + senderRole + "), type=" + messageType
                + ", rounds=" + rounds + ", success=" + success + ", content="
                + (content != null ? content.substring(0, Math.min(100, content.length())) : "null") + "}";
    }
}
