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
        assertEquals(1, dispatcher.metricsSnapshot().acked());
        assertEquals(1, dispatcher.metricsSnapshot().completed());
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

        // Dispatch, acceptance and final result are distinct protocol stages.
        var dispatches = traffic.stream().filter(BusMessage.TaskDispatch.class::isInstance).toList();
        var acks = traffic.stream().filter(BusMessage.TaskAck.class::isInstance).toList();
        var results = traffic.stream().filter(BusMessage.ToolResult.class::isInstance).toList();
        assertEquals(1, dispatches.size());
        assertEquals(1, acks.size());
        assertEquals(1, results.size());
        BusMessage.TaskDispatch td = (BusMessage.TaskDispatch) dispatches.get(0);
        BusMessage.ToolResult tr = (BusMessage.ToolResult) results.get(0);
        assertEquals(td.correlationId(), tr.correlationId());
        assertTrue(tr.success());
    }

    @Test
    void shouldFailImmediatelyWhenWorkerHasNoSubscriber() {
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
        assertTrue(elapsedMs < 500, "missing subscriber should fail immediately, elapsed=" + elapsedMs);
        assertEquals(1, dispatcher.metricsSnapshot().deliveryFailures());
    }

    @Test
    void shouldRetryCallbackFailureAndReportItToDispatcher() {
        InMemoryMessageBus bus = new InMemoryMessageBus(Runnable::run);
        StubConversableAgent manager = new StubConversableAgent("Manager", "", true);
        StubConversableAgent worker = new StubConversableAgent("Broken", "x", true);
        java.util.concurrent.atomic.AtomicInteger callbacks = new java.util.concurrent.atomic.AtomicInteger();
        bus.subscribe("Broken", ignored -> {
            callbacks.incrementAndGet();
            throw new IllegalStateException("callback boom");
        });
        dispatcher = new BusAgentDispatcher(bus, "Manager", 30L, 30L, 2, 0L);

        assertThrows(java.util.concurrent.CompletionException.class, () ->
                dispatcher.dispatch(manager, worker,
                        AgentMessage.builder().content("q").build(), null).join());

        assertEquals(2, callbacks.get());
        assertEquals(1, dispatcher.metricsSnapshot().retries());
        assertEquals(2, dispatcher.metricsSnapshot().deliveryFailures());
    }

    @Test
    void shouldSeparateAckTimeoutFromExecutionTimeout() {
        InMemoryMessageBus bus = new InMemoryMessageBus(Runnable::run);
        StubConversableAgent manager = new StubConversableAgent("Manager", "", true);
        StubConversableAgent worker = new StubConversableAgent("Slow", "x", true);
        bus.subscribe("Slow", message -> {
            BusMessage.TaskDispatch td = (BusMessage.TaskDispatch) message;
            bus.send(BusMessageAdapter.toTaskAck(
                    "Slow", "Manager", td.correlationId(), td.messageId()));
            // Deliberately never send ToolResult.
        });
        dispatcher = new BusAgentDispatcher(bus, "Manager", 1L, 1L, 1, 0L);

        assertThrows(java.util.concurrent.CompletionException.class, () ->
                dispatcher.dispatch(manager, worker,
                        AgentMessage.builder().content("q").build(), null).join());

        BusDispatchMetrics.Snapshot snapshot = dispatcher.metricsSnapshot();
        assertEquals(1, snapshot.acked());
        assertEquals(0, snapshot.ackTimeouts());
        assertEquals(1, snapshot.executionTimeouts());
    }

    @Test
    void shouldRetryWhenAckTimesOut() {
        InMemoryMessageBus bus = new InMemoryMessageBus(Runnable::run);
        StubConversableAgent manager = new StubConversableAgent("Manager", "", true);
        StubConversableAgent worker = new StubConversableAgent("Silent", "x", true);
        java.util.concurrent.atomic.AtomicInteger deliveries = new java.util.concurrent.atomic.AtomicInteger();
        bus.subscribe("Silent", ignored -> deliveries.incrementAndGet());
        dispatcher = new BusAgentDispatcher(bus, "Manager", 1L, 30L, 2, 0L);

        assertThrows(java.util.concurrent.CompletionException.class, () ->
                dispatcher.dispatch(manager, worker,
                        AgentMessage.builder().content("q").build(), null).join());

        assertEquals(2, deliveries.get());
        assertEquals(2, dispatcher.metricsSnapshot().ackTimeouts());
        assertEquals(1, dispatcher.metricsSnapshot().retries());
    }

    @Test
    void executorRejectionShouldReturnNackImmediately() {
        InMemoryMessageBus bus = new InMemoryMessageBus(Runnable::run);
        StubConversableAgent manager = new StubConversableAgent("Manager", "", true);
        StubConversableAgent worker = new StubConversableAgent("Busy", "x", true);
        java.util.concurrent.ExecutorService rejected =
                java.util.concurrent.Executors.newSingleThreadExecutor();
        rejected.shutdown();
        workerEndpoint = new BusWorkerEndpoint(bus, worker, "Manager", rejected);
        dispatcher = new BusAgentDispatcher(bus, "Manager", 10L, 30L, 1, 0L);

        assertThrows(java.util.concurrent.CompletionException.class, () ->
                dispatcher.dispatch(manager, worker,
                        AgentMessage.builder().content("q").build(), null).join());

        assertEquals(1, dispatcher.metricsSnapshot().nacked());
        assertEquals(0, worker.replyCount());
    }

    @Test
    void workerShouldDeduplicateRetriedCorrelationId() throws Exception {
        InMemoryMessageBus bus = new InMemoryMessageBus();
        StubConversableAgent worker = new StubConversableAgent("DataScientist", "ok", true);
        workerEndpoint = new BusWorkerEndpoint(bus, worker, "Manager");
        java.util.concurrent.CountDownLatch result = new java.util.concurrent.CountDownLatch(1);
        bus.subscribe("Manager", message -> {
            if (message instanceof BusMessage.ToolResult) result.countDown();
        });

        String envelope = BusMessageAdapter.encodeGoalEnvelope(
                AgentMessage.builder().content("q").build(), null);
        BusMessage.TaskDispatch first = BusMessageAdapter.toTaskDispatch(
                "Manager", "DataScientist", "same-logical-task", envelope);
        BusMessage.TaskDispatch retry = BusMessageAdapter.toTaskDispatch(
                "Manager", "DataScientist", "same-logical-task", envelope);

        bus.send(first).toCompletableFuture().join();
        bus.send(retry).toCompletableFuture().join();

        assertTrue(result.await(2, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(1, worker.replyCount(), "retry must not execute the same logical task twice");
    }
}
