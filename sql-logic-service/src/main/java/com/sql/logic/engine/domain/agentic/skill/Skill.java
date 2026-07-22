package com.sql.logic.engine.domain.agentic.skill;

import java.util.List;
import java.util.Map;

/**
 * A reusable skill definition that can be injected into Agents.
 * <p>
 * Skills encapsulate domain knowledge, analysis workflows, and best practices
 * into a portable package that Agents can load at runtime. Each skill provides
 * a system prompt fragment, optional tool requirements, and knowledge references.
 */
public class Skill {

    private final String name;
    private final String description;
    private final String category;
    private final String promptTemplate;
    private final List<String> requiredTools;
    private final List<String> requiredKnowledge;
    private final Map<String, Object> config;

    public Skill(String name, String description, String category,
                 String promptTemplate, List<String> requiredTools,
                 List<String> requiredKnowledge, Map<String, Object> config) {
        this.name = name;
        this.description = description;
        this.category = category;
        this.promptTemplate = promptTemplate;
        this.requiredTools = requiredTools != null ? List.copyOf(requiredTools) : List.of();
        this.requiredKnowledge = requiredKnowledge != null
                ? List.copyOf(requiredKnowledge) : List.of();
        this.config = config != null ? Map.copyOf(config) : Map.of();
    }

    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getCategory() { return category; }
    public String getPromptTemplate() { return promptTemplate; }
    public List<String> getRequiredTools() { return requiredTools; }
    public List<String> getRequiredKnowledge() { return requiredKnowledge; }
    public Map<String, Object> getConfig() { return config; }

    /**
     * Render the skill's prompt fragment, injecting variables from the context.
     */
    public String renderPrompt(Map<String, Object> variables) {
        if (promptTemplate == null || promptTemplate.isBlank()) {
            return "";
        }
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
        sb.append("## Skill: ").append(name).append("\n");
        sb.append(description).append("\n");
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
}
