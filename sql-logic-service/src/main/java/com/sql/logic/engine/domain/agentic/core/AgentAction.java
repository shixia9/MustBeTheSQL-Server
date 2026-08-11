package com.sql.logic.engine.domain.agentic.core;

import java.util.concurrent.CompletableFuture;

/**
 * A discrete action that an Agent can execute.
 * <p>
 * Actions are registered on {@link ConversableAgent} and executed
 * during the {@code act()} phase of the generate_reply pipeline.
 * Each action has a name, description, and an execute method that
 * receives the current message context and the owning agent.
 */
public interface AgentAction {

    String name();
    String description();

    /**
     * Execute this action with the given message context and agent reference.
     *
     * @param context the current message (contains user input, schema, evidence, etc.)
     * @param agent   the owning agent (provides access to LLM strategy, memory, resources)
     * @return the action's output
     */
    CompletableFuture<ActionOutput> execute(AgentMessage context, Agent agent);
}
