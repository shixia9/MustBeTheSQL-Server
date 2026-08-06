package com.sql.logic.engine.domain.agent.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sql.logic.engine.common.sink.SandboxEventSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Sinks;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Service-module implementation of {@link SandboxEventSink} — bridges the sandbox
 * module's abstract event emission to the agent's reactor-based SSE infrastructure.
 *
 * <p>Encapsulates the SSE event envelope construction that was formerly inlined in
 * {@code SandboxExecutionService}:
 * <ul>
 *   <li>{@code nodeName = "SANDBOX"}</li>
 *   <li>{@code messageType = AgentSseCodec.messageTypeForNode("SANDBOX")} → {@code "TOOL_CALL"}</li>
 *   <li>{@code sequenceNo = 0}</li>
 *   <li>{@code data = <the payload map>}</li>
 * </ul>
 *
 * <p>The serialized JSON is pushed to the per-thread {@link reactor.core.publisher.Sinks.Many}
 * registered in {@link AgentEventSinkRegistry}. When no sink is registered (e.g. the
 * REST manual-execute path), the call silently returns — this is the null-safe
 * contract defined by {@link SandboxEventSink}.
 *
 * <p>This class is the single piece of glue between the sandbox module and the
 * agent SSE infrastructure. Moving it here (rather than keeping it in the sandbox
 * module) is what breaks the former circular dependency.
 */
@Component
public class AgentSandboxEventSink implements SandboxEventSink {

    private static final Logger log = LoggerFactory.getLogger(AgentSandboxEventSink.class);

    private final AgentEventSinkRegistry registry;
    private final AgentSseCodec codec;
    private final ObjectMapper objectMapper;

    public AgentSandboxEventSink(AgentEventSinkRegistry registry,
                                 AgentSseCodec codec,
                                 ObjectMapper objectMapper) {
        this.registry = registry;
        this.codec = codec;
        this.objectMapper = objectMapper;
    }

    @Override
    public void emit(String threadId, String outputType, Map<String, Object> data) {
        Sinks.Many<String> sink = registry.get(threadId);
        if (sink == null) {
            return;
        }
        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("nodeName", "SANDBOX");
            event.put("outputType", outputType);
            event.put("messageType", codec.messageTypeForNode("SANDBOX"));
            event.put("sequenceNo", 0);
            if (data != null && !data.isEmpty()) {
                event.put("data", data);
            }
            sink.tryEmitNext(objectMapper.writeValueAsString(event));
        } catch (Exception e) {
            log.debug("[AgentSandboxEventSink] SSE emit failed ({}): {}", outputType, e.getMessage());
        }
    }
}
