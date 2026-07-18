package com.sql.logic.engine.domain.agentic.profile;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration for an Agent's profile — its name, role, goal, constraints,
 * and prompt templates. Mirrors DB-GPT's {@code ProfileConfig}.
 * <p>
 * Use the {@link Builder} to construct instances. The profile is registered
 * on each {@link com.sql.logic.engine.domain.agentic.core.ConversableAgent}
 * and used by {@code buildSystemPrompt()} and {@code buildUserPrompt()}
 * to render the LLM prompt.
 */
public class ProfileConfig {

    private final String name;
    private final String role;
    private final String goal;
    private final List<String> constraints;
    private final String description;
    private final String systemPromptTemplate;
    private final String userPromptTemplate;
    private final String writeMemoryTemplate;
    private final Map<String, Object> metadata;

    ProfileConfig(Builder builder) {
        this.name = builder.name;
        this.role = builder.role;
        this.goal = builder.goal;
        this.constraints = builder.constraints != null ? List.copyOf(builder.constraints) : List.of();
        this.description = builder.description;
        this.systemPromptTemplate = builder.systemPromptTemplate;
        this.userPromptTemplate = builder.userPromptTemplate;
        this.writeMemoryTemplate = builder.writeMemoryTemplate;
        this.metadata = Collections.unmodifiableMap(new HashMap<>(builder.metadata));
    }

    public String name() { return name; }
    public String role() { return role; }
    public String goal() { return goal; }
    public List<String> constraints() { return constraints; }
    public String description() { return description; }
    public String systemPromptTemplate() { return systemPromptTemplate; }
    public String userPromptTemplate() { return userPromptTemplate; }
    public String writeMemoryTemplate() { return writeMemoryTemplate; }
    public Map<String, Object> metadata() { return metadata; }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String name;
        private String role;
        private String goal;
        private List<String> constraints = List.of();
        private String description;
        private String systemPromptTemplate;
        private String userPromptTemplate;
        private String writeMemoryTemplate;
        private Map<String, Object> metadata = new HashMap<>();

        public Builder name(String v) { this.name = v; return this; }
        public Builder role(String v) { this.role = v; return this; }
        public Builder goal(String v) { this.goal = v; return this; }
        public Builder constraints(List<String> v) { this.constraints = v; return this; }
        public Builder description(String v) { this.description = v; return this; }
        public Builder systemPromptTemplate(String v) { this.systemPromptTemplate = v; return this; }
        public Builder userPromptTemplate(String v) { this.userPromptTemplate = v; return this; }
        public Builder writeMemoryTemplate(String v) { this.writeMemoryTemplate = v; return this; }
        public Builder metadata(Map<String, Object> v) { this.metadata = v != null ? new HashMap<>(v) : new HashMap<>(); return this; }
        public Builder putMetadata(String key, Object value) { this.metadata.put(key, value); return this; }

        public ProfileConfig build() {
            if (name == null || name.isBlank()) throw new IllegalStateException("ProfileConfig.name is required");
            if (role == null || role.isBlank()) throw new IllegalStateException("ProfileConfig.role is required");
            if (goal == null || goal.isBlank()) throw new IllegalStateException("ProfileConfig.goal is required");
            return new ProfileConfig(this);
        }
    }
}
