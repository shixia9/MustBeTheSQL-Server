package com.sql.logic.engine.domain.agent.core;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Sinks;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

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
