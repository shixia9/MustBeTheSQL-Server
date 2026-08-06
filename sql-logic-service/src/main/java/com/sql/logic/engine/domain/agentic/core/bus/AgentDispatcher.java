package com.sql.logic.engine.domain.agentic.core.bus;

import com.sql.logic.engine.domain.agentic.core.Agent;
import com.sql.logic.engine.domain.agentic.core.AgentMessage;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Abstraction over how {@code ManagerAgent} dispatches a unit of work to a
 * teammate and awaits its reply.
 *
 * <p>Introduces three implementations selected by {@link BusOrchestrationMode}:
 * <ul>
 *   <li>{@link DirectAgentDispatcher} — {@code OFF}: direct {@code generateReply}</li>
 *   <li>{@link BypassAgentDispatcher} — {@code BYPASS}: direct call + bus mirror</li>
 *   <li>{@link BusAgentDispatcher} — {@code SWITCH}: bus-mediated request/reply</li>
 * </ul>
 *
 * <p>All implementations MUST preserve the existing dispatch contract: the
 * returned future completes with the worker's reply {@link AgentMessage} (with
 * its {@code actionReport} populated), or completes exceptionally on failure.
 */
public interface AgentDispatcher {

    /**
     * Dispatch {@code goal} to {@code target}, awaiting the worker's reply.
     *
     * @param sender        the dispatching agent (conventionally ManagerAgent)
     * @param target        the worker agent that should produce the reply
     * @param goal          the goal message to execute
     * @param relyMessages  dependency-step context messages (nullable/empty)
     * @return the worker's reply, with action report and success flag set
     */
    CompletableFuture<AgentMessage> dispatch(Agent sender, Agent target,
                                             AgentMessage goal, List<AgentMessage> relyMessages);

    /** The operating mode this dispatcher implements. */
    BusOrchestrationMode mode();
}
