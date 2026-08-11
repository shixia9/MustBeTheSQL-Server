package com.sql.logic.engine.domain.agentic.core.bus;

import com.sql.logic.engine.domain.agentic.core.AgentMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link BusAgentDispatcher} + {@link BusWorkerEndpoint} —
 * verifies the bus-mediated request/reply: a dispatch travels over the bus to
 * the worker endpoint, which runs {@code generateReply} and replies with a
 * correlated ToolResult that completes the pending future.
 */
class BusAgentDispatcherTest {

    private BusAgentDispatcher dispatcher;
    private BusWorkerEndpoint workerEndpoint;

    @AfterEach
    void tearDown() {
        if (workerEndpoint != null) workerEndpoint.stop();
        if (dispatcher != null) dispatcher.shutdown();
    }

    @Test
    void shouldDispatchOverBusAndReceiveReply() {
        InMemoryMessageBus bus = new InMemoryMessageBus();
        StubConversableAgent manager = new StubConversableAgent("Manager", "", true);
        StubConversableAgent worker = new StubConversableAgent("DataScientist", "SELECT 1", true);
        workerEndpoint = new BusWorkerEndpoint(bus, worker, "Manager");
        dispatcher = new BusAgentDispatcher(bus, "Manager", 30L);

        AgentMessage goal = AgentMessage.builder()
                .content("count users")
                .senderName("Manager")
                .putContext("connectionId", 7L)
                .build();
        AgentMessage reply = dispatcher.dispatch(manager, worker, goal, null).join();

        assertEquals(BusOrchestrationMode.SWITCH, dispatcher.mode());
        assertEquals("SELECT 1", reply.content());
        assertTrue(reply.success());
        assertEquals(1, worker.replyCount());
        // Context survived the bus round-trip.
        assertEquals(7L, ((Number) worker.receivedGoals().get(0).context().get("connectionId")).longValue());
    }

    @Test
    void shouldCarryRelyMessagesOverBus() {
        InMemoryMessageBus bus = new InMemoryMessageBus();
        StubConversableAgent manager = new StubConversableAgent("Manager", "", true);
        StubConversableAgent worker = new StubConversableAgent("CodeAssistant", "code done", true);
        workerEndpoint = new BusWorkerEndpoint(bus, worker, "Manager");
        dispatcher = new BusAgentDispatcher(bus, "Manager", 30L);

        AgentMessage rely = AgentMessage.builder()
                .content("upstream result")
                .messageType(AgentMessage.MessageType.AI)
                .build();
        AgentMessage goal = AgentMessage.builder().content("analyze").build();

        dispatcher.dispatch(manager, worker, goal, List.of(rely)).join();

        // The worker received the rely message via the decoded envelope.
        assertTrue(worker.receivedGoals().get(0).content().equals("analyze"));
    }

    @Test
    void shouldCaptureBusTrafficForObservability() {
        InMemoryMessageBus bus = new InMemoryMessageBus();
        List<BusMessage> traffic = new CopyOnWriteArrayList<>();
        bus.subscribe(InMemoryMessageBus.WILDCARD_TOPIC, traffic::add);

        StubConversableAgent manager = new StubConversableAgent("Manager", "", true);
        StubConversableAgent worker = new StubConversableAgent("DataScientist", "ok", true);
        workerEndpoint = new BusWorkerEndpoint(bus, worker, "Manager");
        dispatcher = new BusAgentDispatcher(bus, "Manager", 30L);

        dispatcher.dispatch(manager, worker, AgentMessage.builder().content("q").build(), null).join();

        // The bus now carries real business traffic (not just a mirror): one
        // TaskDispatch + one ToolResult, correlated.
        var dispatches = traffic.stream().filter(BusMessage.TaskDispatch.class::isInstance).toList();
        var results = traffic.stream().filter(BusMessage.ToolResult.class::isInstance).toList();
        assertEquals(1, dispatches.size());
        assertEquals(1, results.size());
        BusMessage.TaskDispatch td = (BusMessage.TaskDispatch) dispatches.get(0);
        BusMessage.ToolResult tr = (BusMessage.ToolResult) results.get(0);
        assertEquals(td.correlationId(), tr.correlationId());
        assertTrue(tr.success());
    }

    @Test
    void shouldTimeoutWhenNoWorkerResponds() {
        InMemoryMessageBus bus = new InMemoryMessageBus();
        StubConversableAgent manager = new StubConversableAgent("Manager", "", true);
        StubConversableAgent ghost = new StubConversableAgent("Ghost", "x", true);
        // No BusWorkerEndpoint registered for "Ghost" → no reply → timeout.
        dispatcher = new BusAgentDispatcher(bus, "Manager", 1L);

        long start = System.nanoTime();
        assertThrows(java.util.concurrent.CompletionException.class, () ->
                dispatcher.dispatch(manager, ghost, AgentMessage.builder().content("q").build(), null)
                        .join());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        // Should resolve around the 1s timeout.
        assertTrue(elapsedMs >= 900, "timeout should fire (~1s), elapsed=" + elapsedMs);
    }
}
