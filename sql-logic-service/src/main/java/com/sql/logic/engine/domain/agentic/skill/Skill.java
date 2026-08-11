package com.sql.logic.engine.domain.agentic.skill;

import java.util.List;
import java.util.Map;

/**
 * A reusable skill definition that can be injected into Agents.
 */
public class Skill {

    private final String name;
    private final String description;
    private final String category;
    private final String promptTemplate;
    private final List<String> requiredTools;
    private final List<String> requiredKnowledge;
    private final Map<String, Object> config;
    private final String version;
    private final List<String> tags;
    private final boolean isPublic;
    private final String authorId;
    private final double[] embedding;

    public Skill(String name, String description, String category,
                 String promptTemplate, List<String> requiredTools,
                 List<String> requiredKnowledge, Map<String, Object> config) {
        this(name, description, category, promptTemplate, requiredTools,
                requiredKnowledge, config, "1.0.0", List.of(), false, null, null);
    }

    public Skill(String name, String description, String category,
                 String promptTemplate, List<String> requiredTools,
                 List<String> requiredKnowledge, Map<String, Object> config,
                 String version, List<String> tags, boolean isPublic,
                 String authorId, double[] embedding) {
        this.name = name;
        this.description = description;
        this.category = category;
        this.promptTemplate = promptTemplate;
        this.requiredTools = requiredTools != null ? List.copyOf(requiredTools) : List.of();
        this.requiredKnowledge = requiredKnowledge != null
                ? List.copyOf(requiredKnowledge) : List.of();
        this.config = config != null ? Map.copyOf(config) : Map.of();
        this.version = version != null ? version : "1.0.0";
        this.tags = tags != null ? List.copyOf(tags) : List.of();
        this.isPublic = isPublic;
        this.authorId = authorId;
        this.embedding = embedding;
    }

    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getCategory() { return category; }
    public String getPromptTemplate() { return promptTemplate; }
    public List<String> getRequiredTools() { return requiredTools; }
    public List<String> getRequiredKnowledge() { return requiredKnowledge; }
    public Map<String, Object> getConfig() { return config; }
    public String getVersion() { return version; }
    public List<String> getTags() { return tags; }
    public boolean isPublic() { return isPublic; }
    public String getAuthorId() { return authorId; }
    public double[] getEmbedding() { return embedding; }

    /**
     * Render the skill's prompt fragment, injecting variables from the context.
     */
    public String renderPrompt(Map<String, Object> variables) {
        if (promptTemplate == null || promptTemplate.isBlank()) return "";
        String result = promptTemplate;
        for (var entry : variables.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}",
                    entry.getValue() != null ? entry.getValue().toString() : "");
        }
        return result;
    }

    /**
     * Build a system prompt section declaring this skill to the Agent.
     */
    public String toSystemPromptFragment() {
        StringBuilder sb = new StringBuilder();
        sb.append("## Skill: ").append(name).append(" (v").append(version).append(")\n");
        sb.append(description).append("\n");
        if (!tags.isEmpty()) {
            sb.append("Tags: ").append(String.join(", ", tags)).append("\n");
        }
        if (!requiredTools.isEmpty()) {
            sb.append("Required tools: ").append(String.join(", ", requiredTools)).append("\n");
        }
        if (!requiredKnowledge.isEmpty()) {
            sb.append("Required knowledge: ")
                    .append(String.join(", ", requiredKnowledge)).append("\n");
        }
        if (promptTemplate != null && !promptTemplate.isBlank()) {
            sb.append("Guidance:\n").append(promptTemplate).append("\n");
        }
        return sb.toString();
    }

    /**
     * Create a copy with public/private flag set.
     */
    public Skill withPublic(boolean isPublic) {
        return new Skill(name, description, category, promptTemplate, requiredTools,
                requiredKnowledge, config, version, tags, isPublic, authorId, embedding);
    }

    /**
     * Create a copy with embedding set.
     */
    public Skill withEmbedding(double[] embedding) {
        return new Skill(name, description, category, promptTemplate, requiredTools,
                requiredKnowledge, config, version, tags, isPublic, authorId, embedding);
    }
}
