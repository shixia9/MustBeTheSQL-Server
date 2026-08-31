package com.sql.logic.engine.domain.agent.core;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * Low-cardinality HITL metrics for operational diagnosis.
 *
 * <p>Thread ids, user ids and checkpoint ids are intentionally not metric tags;
 * they belong in logs. This keeps the metrics registry bounded under load.</p>
 */
@Component
public class HitlMetrics {

    private final MeterRegistry registry;

    public HitlMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void sessionRegistered() {
        counter("registered").increment();
    }

    public void claim(String status) {
        counter(status == null ? "unknown" : status.toLowerCase()).increment();
    }

    public void resume(String outcome) {
        Counter.builder("sql.agentic.hitl.resume")
                .tag("outcome", outcome == null ? "unknown" : outcome.toLowerCase())
                .register(registry)
                .increment();
    }

    public Timer.Sample startResumeTimer() {
        return Timer.start(registry);
    }

    public void stopResumeTimer(Timer.Sample sample, String outcome) {
        if (sample == null) return;
        sample.stop(Timer.builder("sql.agentic.hitl.resume.latency")
                .tag("outcome", outcome == null ? "unknown" : outcome.toLowerCase())
                .register(registry));
    }

    public void gate(boolean autoConfirm, boolean needsReview) {
        Counter.builder("sql.agentic.hitl.gate")
                .tag("auto_confirm", Boolean.toString(autoConfirm))
                .tag("needs_review", Boolean.toString(needsReview))
                .register(registry)
                .increment();
    }

    public void checkpointFailure() {
        counter("checkpoint_error").increment();
    }

    private Counter counter(String outcome) {
        return Counter.builder("sql.agentic.hitl.session")
                .tag("outcome", outcome)
                .register(registry);
    }
}
