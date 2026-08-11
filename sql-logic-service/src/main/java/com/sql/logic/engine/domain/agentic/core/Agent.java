package com.sql.logic.engine.domain.agentic.core;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Top-level contract for every Agent in the system.
 * <p>
 * Implementors should extend {@link ConversableAgent} rather than
 * implementing this interface directly.
 */
public interface Agent {

    String name();
    String role();
    String goal();
    List<String> constraints();
    String description();

    /**
     * Send a message asynchronously to another Agent.
     */
    CompletableFuture<Void> send(AgentMessage message, Agent recipient);

    /**
     * Receive and process a message from another Agent.
     */
    CompletableFuture<Void> receive(AgentMessage message, Agent sender);

    /**
     * Core execution pipeline: given an input message, produce a reply.
     *
     * @param receivedMessage     the incoming message
     * @param sender              the Agent that sent the message (nullable)
     * @param relyMessages        messages from dependency steps for context injection
     * @param historicalDialogues historical conversation context
     * @return the agent's reply message with action report and verification info
     */
    CompletableFuture<AgentMessage> generateReply(
            AgentMessage receivedMessage,
            Agent sender,
            List<AgentMessage> relyMessages,
            List<AgentMessage> historicalDialogues
    );

    /**
     * Execute the agent's registered actions against the current message.
     */
    CompletableFuture<ActionOutput> act(AgentMessage message, Agent sender);

    /**
     * Verify the correctness of the agent's action output.
     */
    CompletableFuture<VerifyResult> verify(AgentMessage message, Agent sender);
}
