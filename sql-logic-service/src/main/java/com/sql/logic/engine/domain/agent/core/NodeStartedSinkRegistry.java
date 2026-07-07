package com.sql.logic.engine.domain.agent.core;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Sinks;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Holds, per active agent run (keyed by graph {@code threadId}), a {@link Sinks.Many}
 * onto which the {@code AgentSseStartedListener} pushes STARTED SSE events. The
 * runner's merged flux subscribes to {@link Sinks.Many#asFlux()} for each run.
 *
 * <p>A {@link Sinks.Many} (rather than {@code Flux.create}) is used because the
 * graph lifecycle listener's {@code before()} fires on the graph execution thread
 * which may run before Reactor has subscribed downstream — the sink buffers early
 * emissions so the first STARTED is never lost.
 */
@Component
public class NodeStartedSinkRegistry {

    private final ConcurrentMap<String, Sinks.Many<String>> sinks = new ConcurrentHashMap<>();

    /** Create (or replace) the sink for a run and return it. Replacing is safe: the
     *  prior run's flow completes via doFinally which removes + completes its sink
     *  before resume re-registers the same threadId. */
    public Sinks.Many<String> register(String threadId) {
        Sinks.Many<String> sink = Sinks.many().multicast().onBackpressureBuffer();
        sinks.put(threadId, sink);
        return sink;
    }

    public Sinks.Many<String> get(String threadId) {
        return sinks.get(threadId);
    }

    public Sinks.Many<String> remove(String threadId) {
        return sinks.remove(threadId);
    }
}