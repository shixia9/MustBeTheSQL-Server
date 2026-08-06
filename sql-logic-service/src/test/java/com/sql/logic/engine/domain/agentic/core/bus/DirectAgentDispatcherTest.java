package com.sql.logic.engine.domain.agentic.core.bus;

import com.sql.logic.engine.domain.agentic.core.AgentMessage;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link DirectAgentDispatcher} (OFF mode) — verifies it delegates straight to
 * {@code generateReply} and is the zero-overhead legacy path.
 */
class DirectAgentDispatcherTest {

    @Test
    void shouldDelegateToTargetGenerateReply() {
        StubConversableAgent worker = new StubConversableAgent("DataScientist", "SELECT 1", true);
        DirectAgentDispatcher dispatcher = new DirectAgentDispatcher();
        AgentMessage goal = AgentMessage.builder().content("count").build();

        AgentMessage reply = dispatcher.dispatch(worker, worker, goal, null).join();

        assertEquals(BusOrchestrationMode.OFF, dispatcher.mode());
        assertEquals("SELECT 1", reply.content());
        assertTrue(reply.success());
        assertEquals(1, worker.replyCount());
        assertEquals("count", worker.receivedGoals().get(0).content());
    }

    @Test
    void shouldFailOnNullTarget() {
        DirectAgentDispatcher dispatcher = new DirectAgentDispatcher();
        CompletableFuture<AgentMessage> fut = dispatcher.dispatch(
                new StubConversableAgent("M", "x", true), null,
                AgentMessage.builder().content("q").build(), null);
        assertTrue(fut.isCompletedExceptionally());
    }
}
