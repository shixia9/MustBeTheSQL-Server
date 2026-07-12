package com.sql.logic.engine.domain.agent.config;

import java.util.List;

/**
 * Phase B (B4) resolved runtime configuration for one Agent run.
 * <p>
 * Loaded from {@code agent_entity} (the user's default Agent, or the seeded default)
 * by {@link AgentRuntimeConfigService}. Nodes consult this to decide whether to inject
 * the system prompt, skip memory recall, or adjust RAG parameters. All fields have
 * safe defaults so a missing/empty config never breaks the run.
 */
public record AgentRuntimeConfig(
        Long agentId,
        String name,
        String systemPrompt,
        String welcomeMessage,
        /** Tool keys that are enabled, e.g. ["sql","schema","python","sample"]. */
        List<String> enabledTools,
        int ragTopK,
        double ragScoreThreshold,
        boolean ragEnabled,
        boolean memoryEnabled,
        /** Context overflow strategy: TRUNCATE (default) or SUMMARIZE. */
        String contextStrategy
) {
    /** Sentinel used when no Agent config could be resolved — everything at defaults. */
    public static AgentRuntimeConfig defaults() {
        return new AgentRuntimeConfig(null, null, null, null,
                List.of("sql", "schema", "python", "sample"), 5, 0.6, true, true, "TRUNCATE");
    }

    public boolean isToolEnabled(String toolKey) {
        return enabledTools != null && enabledTools.contains(toolKey);
    }

    public boolean hasSystemPrompt() {
        return systemPrompt != null && !systemPrompt.isBlank();
    }
}
