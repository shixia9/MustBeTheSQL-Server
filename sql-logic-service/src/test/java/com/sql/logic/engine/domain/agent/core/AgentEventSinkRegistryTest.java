package com.sql.logic.engine.domain.agent.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentEventSinkRegistryTest {

    @Test
    void sequenceContinuesAcrossSinkReplacementUntilRunCompletes() {
        AgentEventSinkRegistry registry = new AgentEventSinkRegistry();

        registry.register("thread-1");
        assertEquals(1, registry.nextSequence("thread-1"));
        assertEquals(2, registry.nextSequence("thread-1"));

        registry.remove("thread-1");
        registry.register("thread-1");
        assertEquals(3, registry.nextSequence("thread-1"));

        registry.clearSequence("thread-1");
        assertEquals(1, registry.nextSequence("thread-1"));
    }
}
