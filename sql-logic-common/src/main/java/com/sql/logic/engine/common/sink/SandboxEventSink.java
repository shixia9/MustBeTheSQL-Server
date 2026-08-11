package com.sql.logic.engine.common.sink;

import java.util.Map;

/**
 * Abstraction for emitting sandbox SSE events, decoupling the sandbox module
 * from the agent's SSE infrastructure ({@code AgentEventSinkRegistry} /
 * {@code AgentSseCodec}).
 *
 * <p>The sandbox module depends on this interface; the service module provides
 * the implementation ({@code AgentSandboxEventSink}) that wraps the agent's
 * reactor sink registry and SSE codec. This breaks the former circular
 * dependency between {@code domain.sandbox} and {@code domain.agent.core}.
 *
 * <p>Implementations must be <b>null-safe</b>: when no sink is registered for
 * the given thread (e.g. the REST manual-execute path), {@link #emit} should
 * silently return without throwing.
 */
public interface SandboxEventSink {

    /**
     * Emit a sandbox SSE event for the given conversation thread.
     *
     * @param threadId  the conversation thread id (identifies the SSE sink)
     * @param outputType the event output type (e.g. {@code "STARTED"},
     *                   {@code "stream"}, {@code "FINISHED"})
     * @param data      the event payload map (may be null or empty)
     */
    void emit(String threadId, String outputType, Map<String, Object> data);
}
