package com.sql.logic.engine.domain.agentic.core.bus;

import com.sql.logic.engine.domain.agentic.core.Agent;
import com.sql.logic.engine.domain.agentic.core.AgentMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@link BusOrchestrationMode#SWITCH} dispatcher — M9b bus-mediated request/reply.
 *
 * <p>Replaces the direct {@code generateReply} call with a bus round-trip:
 * <ol>
 *   <li>Generate a {@code correlationId}, register a pending {@link CompletableFuture}.</li>
 *   <li>{@code bus.send(TaskDispatch{target, correlationId, envelope})}.</li>
 *   <li>The matching {@link BusWorkerEndpoint} (subscribed to the worker's topic)
 *       decodes the envelope, runs {@code generateReply}, and {@code bus.send}s
 *       back a {@code ToolResult} carrying the same {@code correlationId}.</li>
 *   <li>This dispatcher's reply subscription (on the manager's topic) matches the
 *       {@code correlationId} and completes the pending future.</li>
 * </ol>
 *
 * <p>This makes the bus the channel of record for business messages (REQ-02
 * AC-3/US-3) while {@code NEXT_NODE} remains in graph state for edge routing
 * (AC-5). Synchronous orchestration semantics are preserved: {@code dispatch}
 * returns a future that completes with the worker's reply, so {@code ManagerAgent}'s
 * {@code .join()} call sites behave exactly as before.
 */
public final class BusAgentDispatcher implements AgentDispatcher {

    private static final Logger log = LoggerFactory.getLogger(BusAgentDispatcher.class);

    private final AgentMessageBus bus;
    private final String managerName;
    private final long timeoutSeconds;
    private final ConcurrentHashMap<String, CompletableFuture<AgentMessage>> pending =
            new ConcurrentHashMap<>();
    private final AgentMessageBus.Subscription replySub;

    public BusAgentDispatcher(AgentMessageBus bus, String managerName, long timeoutSeconds) {
        this.bus = bus;
        this.managerName = managerName != null ? managerName : "Manager";
        this.timeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : 300L;
        // Subscribe to the manager's topic to receive ToolResult replies.
        this.replySub = bus.subscribe(this.managerName, this::handleReply);
    }

    @Override
    public CompletableFuture<AgentMessage> dispatch(Agent sender, Agent target,
                                                    AgentMessage goal, List<AgentMessage> relyMessages) {
        if (target == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("dispatch target agent is null"));
        }
        String correlationId = UUID.randomUUID().toString();
        CompletableFuture<AgentMessage> future = new CompletableFuture<>();
        pending.put(correlationId, future);

        try {
            String envelope = BusMessageAdapter.encodeGoalEnvelope(goal, relyMessages);
            bus.send(BusMessageAdapter.toTaskDispatch(
                    managerName, target.name(), correlationId, envelope));
        } catch (Exception e) {
            pending.remove(correlationId);
            return CompletableFuture.failedFuture(e);
        }

        // Timeout: a worker that never replies must not hang the orchestration.
        return future.orTimeout(timeoutSeconds, TimeUnit.SECONDS).exceptionally(t -> {
            CompletableFuture<AgentMessage> removed = pending.remove(correlationId);
            if (removed != null) {
                Throwable cause = (t instanceof TimeoutException)
                        ? t : (t.getCause() != null ? t.getCause() : t);
                log.warn("[BusDispatcher] dispatch to {} timed out/failed (correlationId={}): {}",
                        target.name(), correlationId, cause.toString());
                throw new RuntimeException("Bus dispatch to " + target.name()
                        + " failed: " + cause.getMessage(), cause);
            }
            // Already completed normally — rethrow the timeout as-is shouldn't happen.
            if (t instanceof RuntimeException re) throw re;
            throw new RuntimeException(t);
        });
    }

    private void handleReply(BusMessage message) {
        if (!(message instanceof BusMessage.ToolResult tr)) return;
        String correlationId = tr.header().correlationId();
        if (correlationId == null) return;
        CompletableFuture<AgentMessage> future = pending.remove(correlationId);
        if (future == null || future.isDone()) return;
        try {
            AgentMessage reply = BusMessageAdapter.decodeReplyEnvelope(tr.result());
            future.complete(reply);
        } catch (Exception e) {
            log.warn("[BusDispatcher] failed to decode reply (correlationId={}): {}",
                    correlationId, e.toString());
            future.completeExceptionally(e);
        }
    }

    /** Cancel the reply subscription and fail any pending dispatches. Package-private for tests. */
    void shutdown() {
        if (replySub != null) replySub.cancel();
        pending.values().forEach(f -> f.completeExceptionally(
                new IllegalStateException("dispatcher shutdown")));
        pending.clear();
    }

    @Override
    public BusOrchestrationMode mode() {
        return BusOrchestrationMode.SWITCH;
    }
}
