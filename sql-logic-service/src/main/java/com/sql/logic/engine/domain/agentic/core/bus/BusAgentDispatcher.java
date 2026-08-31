package com.sql.logic.engine.domain.agentic.core.bus;

import com.sql.logic.engine.domain.agentic.core.Agent;
import com.sql.logic.engine.domain.agentic.core.AgentMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SWITCH-mode request/reply dispatcher with distinct delivery, acceptance and
 * execution stages.
 *
 * <p>A {@link DeliveryReceipt} proves only that the in-process receiver callback
 * ran. {@link BusMessage.TaskAck} proves that the worker decoded, de-duplicated
 * and submitted the task. {@link BusMessage.ToolResult} is the final business
 * result. Only failures before ACK are retried; retries keep the same
 * correlation id so the worker can suppress duplicate execution.
 */
public final class BusAgentDispatcher implements AgentDispatcher {

    private static final Logger log = LoggerFactory.getLogger(BusAgentDispatcher.class);

    private final AgentMessageBus bus;
    private final String managerName;
    private final long ackTimeoutMillis;
    private final long executionTimeoutMillis;
    private final int maxDispatchAttempts;
    private final long retryBackoffMillis;
    private final BusDispatchMetrics metrics;
    private final ConcurrentHashMap<String, PendingDispatch> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timers;
    private final AgentMessageBus.Subscription replySub;

    /** Backward-compatible constructor: the supplied timeout is the execution timeout. */
    public BusAgentDispatcher(AgentMessageBus bus, String managerName, long executionTimeoutSeconds) {
        this(bus, managerName, 3L, executionTimeoutSeconds, 3, 100L);
    }

    public BusAgentDispatcher(AgentMessageBus bus, String managerName,
                              long ackTimeoutSeconds, long executionTimeoutSeconds,
                              int maxDispatchAttempts, long retryBackoffMillis) {
        this(bus, managerName, ackTimeoutSeconds, executionTimeoutSeconds,
                maxDispatchAttempts, retryBackoffMillis, new BusDispatchMetrics());
    }

    public BusAgentDispatcher(AgentMessageBus bus, String managerName,
                              long ackTimeoutSeconds, long executionTimeoutSeconds,
                              int maxDispatchAttempts, long retryBackoffMillis,
                              BusDispatchMetrics metrics) {
        this.bus = bus;
        this.managerName = managerName != null ? managerName : "Manager";
        this.ackTimeoutMillis = TimeUnit.SECONDS.toMillis(ackTimeoutSeconds > 0 ? ackTimeoutSeconds : 3L);
        this.executionTimeoutMillis = TimeUnit.SECONDS.toMillis(
                executionTimeoutSeconds > 0 ? executionTimeoutSeconds : 300L);
        this.maxDispatchAttempts = maxDispatchAttempts > 0 ? maxDispatchAttempts : 1;
        this.retryBackoffMillis = Math.max(0L, retryBackoffMillis);
        this.metrics = metrics != null ? metrics : new BusDispatchMetrics();
        this.timers = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "bus-dispatch-timeouts");
            thread.setDaemon(true);
            return thread;
        });
        this.replySub = bus.subscribe(this.managerName, this::handleReply);
    }

    @Override
    public CompletableFuture<AgentMessage> dispatch(Agent sender, Agent target,
                                                    AgentMessage goal,
                                                    List<AgentMessage> relyMessages) {
        if (target == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("dispatch target agent is null"));
        }

        String correlationId = UUID.randomUUID().toString();
        final String envelope;
        try {
            envelope = BusMessageAdapter.encodeGoalEnvelope(goal, relyMessages);
        } catch (Exception error) {
            return CompletableFuture.failedFuture(error);
        }

        PendingDispatch task = new PendingDispatch(
                correlationId, target.name(), envelope, System.nanoTime());
        pending.put(correlationId, task);
        metrics.recordDispatch();
        task.result.whenComplete((ignored, error) -> pending.remove(correlationId, task));
        sendAttempt(task);
        return task.result;
    }

    private void sendAttempt(PendingDispatch task) {
        if (task.result.isDone() || task.acknowledged.get()) return;

        int attempt = task.attempts.incrementAndGet();
        BusMessage.TaskDispatch dispatch = BusMessageAdapter.toTaskDispatch(
                managerName, task.targetName, task.correlationId, task.envelope);
        task.messageAttempts.put(dispatch.messageId(), attempt);

        try {
            bus.send(dispatch).whenComplete((receipt, error) -> {
                if (task.result.isDone() || task.acknowledged.get()) return;
                if (error != null) {
                    metrics.recordDeliveryFailure();
                    retryOrFail(task, attempt,
                            "bus delivery failed: " + messageOf(error));
                    return;
                }
                if (receipt == null || receipt.matchedReceivers() == 0) {
                    metrics.recordDeliveryFailure();
                    failClaimedAttempt(task, attempt, new IllegalStateException(
                            "No subscriber registered for worker " + task.targetName));
                    return;
                }
                if (!receipt.delivered()) {
                    metrics.recordDeliveryFailure();
                    retryOrFail(task, attempt, "receiver callback failed: "
                            + String.join("; ", receipt.errors()));
                    return;
                }
                if (receipt.failedReceivers() > 0) metrics.recordDeliveryFailure();
                scheduleAckTimeout(task, attempt);
            });
        } catch (Exception error) {
            metrics.recordDeliveryFailure();
            retryOrFail(task, attempt, "bus send failed: " + messageOf(error));
        }
    }

    private void scheduleAckTimeout(PendingDispatch task, int attempt) {
        timers.schedule(() -> {
            if (task.result.isDone() || task.acknowledged.get()) return;
            if (!claimAttempt(task, attempt)) return;
            metrics.recordAckTimeout();
            retryClaimedOrFail(task, attempt,
                    "ACK timed out after " + ackTimeoutMillis + "ms");
        }, ackTimeoutMillis, TimeUnit.MILLISECONDS);
    }

    private void retryOrFail(PendingDispatch task, int attempt, String reason) {
        if (!claimAttempt(task, attempt)) return;
        retryClaimedOrFail(task, attempt, reason);
    }

    private void retryClaimedOrFail(PendingDispatch task, int attempt, String reason) {
        if (attempt >= maxDispatchAttempts) {
            fail(task, new IllegalStateException("Bus dispatch to " + task.targetName
                    + " failed after " + attempt + " attempt(s): " + reason));
            return;
        }

        metrics.recordRetry();
        long delay = retryBackoffMillis * attempt;
        log.warn("[BusDispatcher] retrying {} (correlationId={}, attempt={}/{}): {}",
                task.targetName, task.correlationId, attempt + 1, maxDispatchAttempts, reason);
        timers.schedule(() -> sendAttempt(task), delay, TimeUnit.MILLISECONDS);
    }

    private void failClaimedAttempt(PendingDispatch task, int attempt, Throwable error) {
        if (claimAttempt(task, attempt)) fail(task, error);
    }

    private boolean claimAttempt(PendingDispatch task, int attempt) {
        return !task.result.isDone() && !task.acknowledged.get()
                && task.resolvedAttempt.compareAndSet(attempt - 1, attempt);
    }

    private void handleReply(BusMessage message) {
        String correlationId = message.correlationId();
        if (correlationId == null) return;
        PendingDispatch task = pending.get(correlationId);
        if (task == null || task.result.isDone()) return;

        switch (message) {
            case BusMessage.TaskAck ack -> acknowledge(task, ack.dispatchMessageId());
            case BusMessage.TaskNack nack -> handleNack(task, nack);
            case BusMessage.ToolResult result -> handleToolResult(task, result);
            default -> { }
        }
    }

    private void acknowledge(PendingDispatch task, String dispatchMessageId) {
        if (dispatchMessageId == null) return;
        if (!task.messageAttempts.containsKey(dispatchMessageId)) return;
        if (!task.acknowledged.compareAndSet(false, true)) return;

        task.acknowledgedAtNanos = System.nanoTime();
        metrics.recordAck(task.acknowledgedAtNanos - task.startedAtNanos);
        timers.schedule(() -> {
            if (task.result.isDone() || task.resultReceived.get()) return;
            metrics.recordExecutionTimeout();
            fail(task, new IllegalStateException("Worker " + task.targetName
                    + " acknowledged the task but execution timed out after "
                    + executionTimeoutMillis + "ms"));
        }, executionTimeoutMillis, TimeUnit.MILLISECONDS);
    }

    private void handleNack(PendingDispatch task, BusMessage.TaskNack nack) {
        if (nack.dispatchMessageId() == null) return;
        Integer attempt = task.messageAttempts.get(nack.dispatchMessageId());
        if (attempt == null || !claimAttempt(task, attempt)) return;
        metrics.recordNack();

        String reason = "worker NACK " + nack.errorCode() + ": " + nack.reason();
        if (!nack.retryable() || attempt >= maxDispatchAttempts) {
            fail(task, new IllegalStateException(reason));
            return;
        }

        metrics.recordRetry();
        timers.schedule(() -> sendAttempt(task), retryBackoffMillis * attempt,
                TimeUnit.MILLISECONDS);
    }

    private void handleToolResult(PendingDispatch task, BusMessage.ToolResult result) {
        // A result is stronger evidence than an ACK. This also tolerates harmless
        // reordering between the two independently scheduled bus callbacks.
        if (!task.acknowledged.get()) {
            String dispatchMessageId = task.messageAttempts.keySet().stream()
                    .findFirst().orElse(null);
            if (dispatchMessageId != null) acknowledge(task, dispatchMessageId);
        }
        try {
            AgentMessage reply = BusMessageAdapter.decodeReplyEnvelope(result.result());
            if (!task.resultReceived.compareAndSet(false, true)) return;
            long ackedAt = task.acknowledgedAtNanos;
            if (ackedAt > 0L) {
                metrics.recordExecutionLatency(System.nanoTime() - ackedAt);
            }
            metrics.recordCompleted();
            task.result.complete(reply);
        } catch (Exception error) {
            fail(task, error);
        }
    }

    private void fail(PendingDispatch task, Throwable error) {
        if (task.result.completeExceptionally(error)) {
            log.warn("[BusDispatcher] dispatch to {} failed (correlationId={}): {}",
                    task.targetName, task.correlationId, error.toString());
        }
    }

    private static String messageOf(Throwable error) {
        Throwable cause = error.getCause() != null ? error.getCause() : error;
        return cause.getMessage() != null ? cause.getMessage() : cause.toString();
    }

    public BusDispatchMetrics.Snapshot metricsSnapshot() {
        return metrics.snapshot();
    }

    /** Cancel subscriptions/timers and fail any pending dispatches. */
    void shutdown() {
        if (replySub != null) replySub.cancel();
        timers.shutdownNow();
        pending.values().forEach(task -> task.result.completeExceptionally(
                new IllegalStateException("dispatcher shutdown")));
        pending.clear();
    }

    @Override
    public BusOrchestrationMode mode() {
        return BusOrchestrationMode.SWITCH;
    }

    private static final class PendingDispatch {
        private final String correlationId;
        private final String targetName;
        private final String envelope;
        private final long startedAtNanos;
        private final CompletableFuture<AgentMessage> result = new CompletableFuture<>();
        private final AtomicBoolean acknowledged = new AtomicBoolean();
        private final AtomicBoolean resultReceived = new AtomicBoolean();
        private final AtomicInteger attempts = new AtomicInteger();
        private final AtomicInteger resolvedAttempt = new AtomicInteger();
        private final ConcurrentHashMap<String, Integer> messageAttempts = new ConcurrentHashMap<>();
        private volatile long acknowledgedAtNanos;

        private PendingDispatch(String correlationId, String targetName,
                                String envelope, long startedAtNanos) {
            this.correlationId = correlationId;
            this.targetName = targetName;
            this.envelope = envelope;
            this.startedAtNanos = startedAtNanos;
        }
    }
}
