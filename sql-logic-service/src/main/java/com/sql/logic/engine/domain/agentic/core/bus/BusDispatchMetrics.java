package com.sql.logic.engine.domain.agentic.core.bus;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.concurrent.atomic.LongAdder;

/** Lightweight in-process counters for SWITCH-mode delivery health. */
public final class BusDispatchMetrics {

    private final MeterRegistry registry;

    private final LongAdder dispatched = new LongAdder();
    private final LongAdder acked = new LongAdder();
    private final LongAdder nacked = new LongAdder();
    private final LongAdder retries = new LongAdder();
    private final LongAdder deliveryFailures = new LongAdder();
    private final LongAdder ackTimeouts = new LongAdder();
    private final LongAdder executionTimeouts = new LongAdder();
    private final LongAdder completed = new LongAdder();
    private final LongAdder totalAckLatencyNanos = new LongAdder();
    private final LongAdder totalExecutionLatencyNanos = new LongAdder();

    public BusDispatchMetrics() {
        this(null);
    }

    public BusDispatchMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    void recordDispatch() {
        dispatched.increment();
        increment("sql.agentic.bus.dispatch");
    }
    void recordAck(long latencyNanos) {
        acked.increment();
        totalAckLatencyNanos.add(Math.max(0L, latencyNanos));
        increment("sql.agentic.bus.ack");
        recordTimer("sql.agentic.bus.ack.latency", latencyNanos);
    }
    void recordNack() {
        nacked.increment();
        increment("sql.agentic.bus.nack");
    }
    void recordRetry() {
        retries.increment();
        increment("sql.agentic.bus.retry");
    }
    void recordDeliveryFailure() {
        deliveryFailures.increment();
        increment("sql.agentic.bus.delivery.failure");
    }
    void recordAckTimeout() {
        ackTimeouts.increment();
        increment("sql.agentic.bus.timeout", "phase", "ack");
    }
    void recordExecutionTimeout() {
        executionTimeouts.increment();
        increment("sql.agentic.bus.timeout", "phase", "execution");
    }
    void recordCompleted() {
        completed.increment();
        increment("sql.agentic.bus.completed");
    }
    void recordExecutionLatency(long latencyNanos) {
        totalExecutionLatencyNanos.add(Math.max(0L, latencyNanos));
        recordTimer("sql.agentic.bus.execution.latency", latencyNanos);
    }

    private void increment(String name, String... tags) {
        if (registry != null) registry.counter(name, tags).increment();
    }

    private void recordTimer(String name, long latencyNanos) {
        if (registry != null) {
            Timer.builder(name).register(registry)
                    .record(Duration.ofNanos(Math.max(0L, latencyNanos)));
        }
    }

    public Snapshot snapshot() {
        return new Snapshot(dispatched.sum(), acked.sum(), nacked.sum(), retries.sum(),
                deliveryFailures.sum(), ackTimeouts.sum(), executionTimeouts.sum(), completed.sum(),
                totalAckLatencyNanos.sum(), totalExecutionLatencyNanos.sum());
    }

    public record Snapshot(
            long dispatched,
            long acked,
            long nacked,
            long retries,
            long deliveryFailures,
            long ackTimeouts,
            long executionTimeouts,
            long completed,
            long totalAckLatencyNanos,
            long totalExecutionLatencyNanos
    ) {
        public long averageAckLatencyNanos() {
            return acked == 0 ? 0L : totalAckLatencyNanos / acked;
        }

        public long averageExecutionLatencyNanos() {
            return completed == 0 ? 0L : totalExecutionLatencyNanos / completed;
        }
    }
}
