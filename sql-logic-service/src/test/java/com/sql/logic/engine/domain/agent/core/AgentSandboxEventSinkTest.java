package com.sql.logic.engine.domain.agent.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Sinks;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AgentSandboxEventSink} — the service-module implementation
 * of {@link com.sql.logic.engine.common.sink.SandboxEventSink} that bridges the
 * sandbox module's abstract event emission to the agent's reactor-based SSE
 * infrastructure.
 *
 * <p>Covers the SSE JSON envelope structure, null-sink no-op behaviour, and
 * serialization exception safety — concerns that moved out of
 * {@code SandboxExecutionServiceTest} when the sink was abstracted.
 */
class AgentSandboxEventSinkTest {

    private AgentEventSinkRegistry registry;
    private AgentSseCodec codec;
    private ObjectMapper objectMapper;
    private AgentSandboxEventSink sink;

    @BeforeEach
    void setUp() {
        registry = mock(AgentEventSinkRegistry.class);
        codec = mock(AgentSseCodec.class);
        objectMapper = new ObjectMapper();
        sink = new AgentSandboxEventSink(registry, codec, objectMapper);

        when(codec.messageTypeForNode("SANDBOX")).thenReturn("TOOL_CALL");
    }

    @Test
    void emitShouldPushCanonicalEventJsonToSink() throws Exception {
        Sinks.Many<String> reactorSink = Sinks.many().multicast().onBackpressureBuffer();
        when(registry.get("thread-1")).thenReturn(reactorSink);

        java.util.List<String> emitted = new java.util.concurrent.CopyOnWriteArrayList<>();
        reactorSink.asFlux().subscribe(emitted::add);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("language", "python");
        data.put("code", "print(42)");

        sink.emit("thread-1", "STARTED", data);

        assertEquals(1, emitted.size());
        JsonNode event = objectMapper.readTree(emitted.get(0));
        assertEquals("SANDBOX", event.get("nodeName").asText());
        assertEquals("STARTED", event.get("outputType").asText());
        assertEquals("TOOL_CALL", event.get("messageType").asText());
        assertEquals(0, event.get("sequenceNo").asInt());
        assertEquals("python", event.get("data").get("language").asText());
        assertEquals("print(42)", event.get("data").get("code").asText());
    }

    @Test
    void emitShouldBeNoOpWhenNoSinkRegistered() {
        when(registry.get("thread-1")).thenReturn(null);

        // Should not throw — null-sink is the REST manual-execute path
        sink.emit("thread-1", "STARTED", Map.of("language", "python"));

        // Verify only the registry lookup happened, no exception
        verify(registry).get("thread-1");
        verifyNoMoreInteractions(registry);
    }

    @Test
    void emitShouldOmitDataFieldWhenDataIsNull() throws Exception {
        Sinks.Many<String> reactorSink = Sinks.many().multicast().onBackpressureBuffer();
        when(registry.get("thread-2")).thenReturn(reactorSink);

        java.util.List<String> emitted = new java.util.concurrent.CopyOnWriteArrayList<>();
        reactorSink.asFlux().subscribe(emitted::add);

        sink.emit("thread-2", "FINISHED", null);

        assertEquals(1, emitted.size());
        JsonNode event = objectMapper.readTree(emitted.get(0));
        assertEquals("FINISHED", event.get("outputType").asText());
        assertFalse(event.has("data"), "data field should be absent when data is null");
    }

    @Test
    void emitShouldOmitDataFieldWhenDataIsEmpty() throws Exception {
        Sinks.Many<String> reactorSink = Sinks.many().multicast().onBackpressureBuffer();
        when(registry.get("thread-3")).thenReturn(reactorSink);

        java.util.List<String> emitted = new java.util.concurrent.CopyOnWriteArrayList<>();
        reactorSink.asFlux().subscribe(emitted::add);

        sink.emit("thread-3", "STARTED", new LinkedHashMap<>());

        assertEquals(1, emitted.size());
        JsonNode event = objectMapper.readTree(emitted.get(0));
        assertFalse(event.has("data"), "data field should be absent when data is empty");
    }

    @Test
    void emitShouldNotThrowOnSerializationFailure() throws Exception {
        Sinks.Many<String> reactorSink = Sinks.many().multicast().onBackpressureBuffer();
        when(registry.get("thread-err")).thenReturn(reactorSink);

        // Use a mock ObjectMapper that throws JsonProcessingException to simulate
        // serialization failure (the production catch block handles Exception).
        ObjectMapper badMapper = mock(ObjectMapper.class);
        when(badMapper.writeValueAsString(any())).thenThrow(
                new com.fasterxml.jackson.core.JsonProcessingException("Simulated failure") {});
        AgentSandboxEventSink sinkWithBadMapper = new AgentSandboxEventSink(registry, codec, badMapper);

        // Should swallow the exception and not throw
        assertDoesNotThrow(() -> sinkWithBadMapper.emit("thread-err", "STARTED", Map.of("k", "v")));
    }

    @Test
    void emitShouldHandleStreamChunksCorrectly() throws Exception {
        Sinks.Many<String> reactorSink = Sinks.many().multicast().onBackpressureBuffer();
        when(registry.get("thread-stream")).thenReturn(reactorSink);

        java.util.List<String> emitted = new java.util.concurrent.CopyOnWriteArrayList<>();
        reactorSink.asFlux().subscribe(emitted::add);

        Map<String, Object> chunkData = new LinkedHashMap<>();
        chunkData.put("index", 0);
        chunkData.put("chunk", "hello");
        chunkData.put("isError", false);

        sink.emit("thread-stream", "stream", chunkData);

        assertEquals(1, emitted.size());
        JsonNode event = objectMapper.readTree(emitted.get(0));
        assertEquals("stream", event.get("outputType").asText());
        assertEquals(0, event.get("data").get("index").asInt());
        assertEquals("hello", event.get("data").get("chunk").asText());
        assertFalse(event.get("data").get("isError").asBoolean());
    }
}
