package com.sql.logic.engine.domain.agentic.core.bus;

import com.sql.logic.engine.domain.agentic.core.Agent;
import com.sql.logic.engine.domain.agentic.core.AgentMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * {@link BusOrchestrationMode#BYPASS} dispatcher.
 *
 * <p>Execution is unchanged: a {@link DirectAgentDispatcher} still drives the
 * actual {@code generateReply} call, so behaviour is bit-for-bit identical to
 * {@code OFF} mode. Around that call, each dispatch is <em>mirrored</em> onto
 * the bus as a correlated {@link BusMessage.TaskDispatch} (before) /
 * {@link BusMessage.ToolResult} (after) pair. This makes the communication link
 * observable, replayable, and — via the {@code MessageBusParityIT} test —
 * verifiably equivalent to the direct dispatch, satisfying REQ-02 AC-1/AC-2.
 *
 * <p>Mirroring is best-effort: any bus-side failure is logged and swallowed so
 * that an observation path can never break the orchestration hot path.
 */
public final class BypassAgentDispatcher implements AgentDispatcher {

    private static final Logger log = LoggerFactory.getLogger(BypassAgentDispatcher.class);

    private final AgentMessageBus bus;
    private final AgentDispatcher engine;

    public BypassAgentDispatcher(AgentMessageBus bus, AgentDispatcher engine) {
        this.bus = bus;
        this.engine = engine != null ? engine : new DirectAgentDispatcher();
    }

    @Override
    public CompletableFuture<AgentMessage> dispatch(Agent sender, Agent target,
                                                    AgentMessage goal, List<AgentMessage> relyMessages) {
        String senderName = sender != null ? sender.name() : "Manager";
        String targetName = target != null ? target.name() : "unknown";
        String correlationId = UUID.randomUUID().toString();

        // Mirror the outbound dispatch BEFORE executing (best-effort).
        try {
            String envelope = BusMessageAdapter.encodeGoalEnvelope(goal, relyMessages);
            bus.send(BusMessageAdapter.toTaskDispatch(senderName, targetName, correlationId, envelope));
        } catch (Exception e) {
            log.warn("[Bypass] mirror TaskDispatch failed (target={}): {}", targetName, e.toString());
        }

        return engine.dispatch(sender, target, goal, relyMessages)
                .handle((reply, err) -> {
                    // Mirror the inbound reply / error AFTER (best-effort).
                    try {
                        if (err != null) {
                            bus.send(BusMessageAdapter.toToolResult(
                                    targetName, senderName, correlationId, false,
                                    BusMessageAdapter.encodeErrorEnvelope(err)));
                        } else {
                            boolean success = reply != null && reply.success();
                            String envelope = reply != null
                                    ? BusMessageAdapter.encodeReplyEnvelope(reply)
                                    : BusMessageAdapter.encodeErrorEnvelope(
                                            new IllegalStateException("null reply"));
                            bus.send(BusMessageAdapter.toToolResult(
                                    targetName, senderName, correlationId, success, envelope));
                        }
                    } catch (Exception e) {
                        log.warn("[Bypass] mirror ToolResult failed (target={}): {}",
                                targetName, e.toString());
                    }
                    // Re-throw the original error so callers see the real failure.
                    if (err != null) {
                        if (err instanceof RuntimeException re) throw re;
                        throw new RuntimeException(err);
                    }
                    return reply;
                });
    }

    @Override
    public BusOrchestrationMode mode() {
        return BusOrchestrationMode.BYPASS;
    }
}
