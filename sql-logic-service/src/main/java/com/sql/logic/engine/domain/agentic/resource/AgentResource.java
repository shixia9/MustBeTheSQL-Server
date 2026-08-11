package com.sql.logic.engine.domain.agentic.resource;

import com.sql.logic.engine.domain.agentic.core.AgentMessage;

/**
 * Resource abstraction for Agent knowledge and tool bindings.
 * <p>
 * Resources provide domain-specific context (schema DDL, knowledge base entries,
 * tool definitions) that is injected into the agent's system prompt during
 * {@code loadThinkingMessages()}. Modeled after DB-GPT's {@code Resource}.
 */
public interface AgentResource {

    String name();

    /**
     * Produce the prompt fragment for this resource given the current observation.
     * Returns an empty string if the resource has nothing to contribute.
     */
    String getPrompt(String observation);

    /**
     * Produce a prompt fragment with full message context (for resources that
     * need to inspect the full AgentMessage for richer context injection).
     */
    default String getPrompt(AgentMessage message) {
        return getPrompt(message != null ? message.content() : "");
    }
}
