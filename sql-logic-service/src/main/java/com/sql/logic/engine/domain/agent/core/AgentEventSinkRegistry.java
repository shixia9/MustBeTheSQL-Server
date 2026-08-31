package com.sql.logic.engine.domain.agent.core;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Sinks;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Registry for custom SSE events emitted by agents during graph node execution.
 * <p>
 * Unlike {@link NodeStartedSinkRegistry} which is driven by the StateGraph lifecycle
 * listener, this registry allows agents (e.g. ManagerAgent) to emit SSE events for
 * sub-steps that execute inside a single graph node. The AgenticRunner merges the
 * registered sink's flux into the unified SSE stream.
 */
@Component
public class AgentEventSinkRegistry {

    private final ConcurrentMap<String, Sinks.Many<String>> sinks = new ConcurrentHashMap<>();
    /** Run-local sequence counters survive a HITL pause and resume. */
    private final ConcurrentMap<String, AtomicInteger> sequences = new ConcurrentHashMap<>();

    public Sinks.Many<String> register(String threadId) {
        Sinks.Many<String> sink = Sinks.many().multicast().onBackpressureBuffer();
        sinks.put(threadId, sink);
        sequences.computeIfAbsent(threadId, ignored -> new AtomicInteger());
        return sink;
    }

    public Sinks.Many<String> get(String threadId) {
        return sinks.get(threadId);
    }

    public Sinks.Many<String> remove(String threadId) {
        return sinks.remove(threadId);
    }

    /** Return the next positive SSE sequence for this run. */
    public int nextSequence(String threadId) {
        if (threadId == null || threadId.isBlank()) return 0;
        return sequences.computeIfAbsent(threadId, ignored -> new AtomicInteger())
                .incrementAndGet();
    }

    /** Clear sequence state only after a run has really completed. */
    public void clearSequence(String threadId) {
        if (threadId != null && !threadId.isBlank()) sequences.remove(threadId);
    }
}
