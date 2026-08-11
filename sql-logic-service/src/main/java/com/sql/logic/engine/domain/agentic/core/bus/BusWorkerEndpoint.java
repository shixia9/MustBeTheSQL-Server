package com.sql.logic.engine.domain.agentic.core.bus;

import com.sql.logic.engine.domain.agentic.core.Agent;
import com.sql.logic.engine.domain.agentic.core.AgentMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SWITCH-mode worker side: subscribes a worker Agent to its own topic on the
 * bus and bridges inbound {@link BusMessage.TaskDispatch} messages into a real
 * {@link Agent#generateReply} execution, replying with a {@link BusMessage.ToolResult}
 * that echoes the dispatch's {@code correlationId}.
 *
 * <p>One endpoint per worker. Lifecycle managed by {@link BusWorkerEndpointRegistrar}.
 * Execution runs on a dedicated virtual-thread executor so a slow worker never
 * blocks the bus dispatch thread.
 */
public final class BusWorkerEndpoint {

    private static final Logger log = LoggerFactory.getLogger(BusWorkerEndpoint.class);

    private final AgentMessageBus bus;
    private final Agent worker;
    private final String managerName;
    private final ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
    private final AgentMessageBus.Subscription sub;

    public BusWorkerEndpoint(AgentMessageBus bus, Agent worker, String managerName) {
        this.bus = bus;
        this.worker = worker;
        this.managerName = managerName != null ? managerName : "Manager";
        this.sub = bus.subscribe(worker.name(), this::onDispatch);
    }

    private void onDispatch(BusMessage message) {
        if (!(message instanceof BusMessage.TaskDispatch td)) return;
        // Run on a worker thread so the bus dispatcher returns immediately.
        exec.submit(() -> execute(td));
    }

    private void execute(BusMessage.TaskDispatch td) {
        String correlationId = td.header().correlationId();
        try {
            BusMessageAdapter.DecodedGoal decoded = BusMessageAdapter.decodeGoalEnvelope(td.task());
            // sender is unused by the generateReply pipeline (verified: ConversableAgent
            // never reads the sender arg), so null is safe and avoids coupling the
            // endpoint to a manager Agent reference.
            worker.generateReply(decoded.goal(), null, decoded.relyMessages(), null)
                    .whenComplete((reply, err) -> sendReply(td, correlationId, reply, err));
        } catch (Exception e) {
            log.warn("[BusWorker:{}] failed to handle dispatch (correlationId={}): {}",
                    worker.name(), correlationId, e.toString());
            bus.send(BusMessageAdapter.toToolResult(
                    worker.name(), managerName, correlationId, false,
                    BusMessageAdapter.encodeErrorEnvelope(e)));
        }
    }

    private void sendReply(BusMessage.TaskDispatch td, String correlationId,
                           AgentMessage reply, Throwable err) {
        try {
            if (err != null) {
                bus.send(BusMessageAdapter.toToolResult(
                        worker.name(), managerName, correlationId, false,
                        BusMessageAdapter.encodeErrorEnvelope(err)));
            } else {
                boolean success = reply != null && reply.success();
                String envelope = reply != null
                        ? BusMessageAdapter.encodeReplyEnvelope(reply)
                        : BusMessageAdapter.encodeErrorEnvelope(
                                new IllegalStateException("null reply"));
                bus.send(BusMessageAdapter.toToolResult(
                        worker.name(), managerName, correlationId, success, envelope));
            }
        } catch (Exception e) {
            log.warn("[BusWorker:{}] failed to send reply (correlationId={}): {}",
                    worker.name(), correlationId, e.toString());
        }
    }

    /** Stop the subscription and shut down the worker executor. */
    public void stop() {
        if (sub != null) sub.cancel();
        exec.shutdownNow();
    }

    public String workerName() {
        return worker.name();
    }
}
