package com.sql.logic.engine.domain.agentic.core.bus;

import com.sql.logic.engine.domain.agentic.core.Agent;
import com.sql.logic.engine.domain.agentic.core.AgentMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
    private final ExecutorService exec;
    private final AgentMessageBus.Subscription sub;
    private final ConcurrentHashMap<String, TaskState> tasks = new ConcurrentHashMap<>();
    private final AtomicInteger dispatchCount = new AtomicInteger();

    private static final long COMPLETED_TASK_TTL_NANOS = TimeUnit.MINUTES.toNanos(10);

    public BusWorkerEndpoint(AgentMessageBus bus, Agent worker, String managerName) {
        this(bus, worker, managerName, Executors.newVirtualThreadPerTaskExecutor());
    }

    BusWorkerEndpoint(AgentMessageBus bus, Agent worker, String managerName,
                      ExecutorService executor) {
        this.bus = bus;
        this.worker = worker;
        this.managerName = managerName != null ? managerName : "Manager";
        this.exec = executor;
        this.sub = bus.subscribe(worker.name(), this::onDispatch);
    }

    private void onDispatch(BusMessage message) {
        if (!(message instanceof BusMessage.TaskDispatch td)) return;
        cleanupCompletedTasksOccasionally();

        String correlationId = td.correlationId();
        String taskKey = correlationId != null && !correlationId.isBlank()
                ? correlationId : td.messageId();

        final BusMessageAdapter.DecodedGoal decoded;
        try {
            decoded = BusMessageAdapter.decodeGoalEnvelope(td.task());
        } catch (Exception error) {
            sendNack(td, "INVALID_MESSAGE", error.getMessage(), false);
            return;
        }

        TaskState newState = new TaskState();
        TaskState existing = tasks.putIfAbsent(taskKey, newState);
        if (existing != null) {
            // A retry carries the same correlationId. Re-ACK it, but never execute
            // the business task twice. If already complete, replay the cached result.
            sendAck(td);
            BusMessage.ToolResult cached = existing.result;
            if (cached != null) send(cached, "cached result");
            return;
        }

        try {
            // ACK means the task has passed decoding/de-duplication and was accepted
            // by the execution pool. It does not mean business execution completed.
            exec.submit(() -> execute(td, decoded, newState));
        } catch (RejectedExecutionException error) {
            tasks.remove(taskKey, newState);
            sendNack(td, "EXECUTOR_REJECTED", error.getMessage(), true);
            return;
        } catch (RuntimeException error) {
            tasks.remove(taskKey, newState);
            sendNack(td, "SUBMIT_FAILED", error.getMessage(), true);
            return;
        }
        sendAck(td);
    }

    private void execute(BusMessage.TaskDispatch td,
                         BusMessageAdapter.DecodedGoal decoded, TaskState state) {
        String correlationId = td.header().correlationId();
        try {
            // sender is unused by the generateReply pipeline (verified: ConversableAgent
            // never reads the sender arg), so null is safe and avoids coupling the
            // endpoint to a manager Agent reference.
            worker.generateReply(decoded.goal(), null, decoded.relyMessages(), null)
                    .whenComplete((reply, err) -> completeTask(
                            correlationId, state, reply, err));
        } catch (Exception e) {
            log.warn("[BusWorker:{}] failed to handle dispatch (correlationId={}): {}",
                    worker.name(), correlationId, e.toString());
            completeTask(correlationId, state, null, e);
        }
    }

    private void completeTask(String correlationId, TaskState state,
                              AgentMessage reply, Throwable err) {
        try {
            BusMessage.ToolResult result;
            if (err != null) {
                result = BusMessageAdapter.toToolResult(
                        worker.name(), managerName, correlationId, false,
                        BusMessageAdapter.encodeErrorEnvelope(err));
            } else {
                boolean success = reply != null && reply.success();
                String envelope = reply != null
                        ? BusMessageAdapter.encodeReplyEnvelope(reply)
                        : BusMessageAdapter.encodeErrorEnvelope(
                                new IllegalStateException("null reply"));
                result = BusMessageAdapter.toToolResult(
                        worker.name(), managerName, correlationId, success, envelope);
            }
            state.result = result;
            state.completedAtNanos = System.nanoTime();
            send(result, "result");
        } catch (Exception e) {
            log.warn("[BusWorker:{}] failed to send reply (correlationId={}): {}",
                    worker.name(), correlationId, e.toString());
        }
    }

    private void sendAck(BusMessage.TaskDispatch td) {
        send(BusMessageAdapter.toTaskAck(worker.name(), managerName,
                td.correlationId(), td.messageId()), "ACK");
    }

    private void sendNack(BusMessage.TaskDispatch td, String errorCode,
                          String reason, boolean retryable) {
        send(BusMessageAdapter.toTaskNack(worker.name(), managerName,
                td.correlationId(), td.messageId(), errorCode, reason, retryable), "NACK");
    }

    private void send(BusMessage message, String description) {
        try {
            bus.send(message).whenComplete((receipt, error) -> {
                if (error != null) {
                    log.warn("[BusWorker:{}] failed to deliver {}: {}",
                            worker.name(), description, error.toString());
                } else if (receipt == null || !receipt.delivered()) {
                    log.warn("[BusWorker:{}] {} had no successful receiver (messageId={})",
                            worker.name(), description, message.messageId());
                }
            });
        } catch (RuntimeException error) {
            log.warn("[BusWorker:{}] failed to send {}: {}",
                    worker.name(), description, error.toString());
        }
    }

    private void cleanupCompletedTasksOccasionally() {
        if ((dispatchCount.incrementAndGet() & 0xff) != 0) return;
        long cutoff = System.nanoTime() - COMPLETED_TASK_TTL_NANOS;
        tasks.entrySet().removeIf(entry -> {
            long completedAt = entry.getValue().completedAtNanos;
            return completedAt != 0L && completedAt < cutoff;
        });
    }

    /** Stop the subscription and shut down the worker executor. */
    public void stop() {
        if (sub != null) sub.cancel();
        exec.shutdownNow();
        tasks.clear();
    }

    public String workerName() {
        return worker.name();
    }

    private static final class TaskState {
        private volatile BusMessage.ToolResult result;
        private volatile long completedAtNanos;
    }
}
