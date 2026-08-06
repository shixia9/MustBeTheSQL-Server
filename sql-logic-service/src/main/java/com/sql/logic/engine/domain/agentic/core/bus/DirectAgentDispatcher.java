package com.sql.logic.engine.domain.agentic.core.bus;

import com.sql.logic.engine.domain.agentic.core.Agent;
import com.sql.logic.engine.domain.agentic.core.AgentMessage;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * {@link BusOrchestrationMode#OFF} dispatcher — the legacy direct-call path.
 *
 * <p>Delegates straight to {@link Agent#generateReply}. This is the fail-safe
 * default (zero behavioural change) and also the inner engine reused by
 * {@link BypassAgentDispatcher} so that BYPASS mode keeps the exact same
 * execution semantics, merely adding an observational bus mirror.
 */
public final class DirectAgentDispatcher implements AgentDispatcher {

    @Override
    public CompletableFuture<AgentMessage> dispatch(Agent sender, Agent target,
                                                    AgentMessage goal, List<AgentMessage> relyMessages) {
        if (target == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("dispatch target agent is null"));
        }
        return target.generateReply(goal, sender, relyMessages, null);
    }

    @Override
    public BusOrchestrationMode mode() {
        return BusOrchestrationMode.OFF;
    }
}
