package com.sql.logic.engine.domain.agentic.core.bus;

import com.sql.logic.engine.domain.agentic.core.AgentMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * REQ-02 AC-1 / AC-2 — bypass parity verification.
 *
 * <p>Runs a batch of mixed success/failure dispatches through
 * {@link BypassAgentDispatcher} (M9a dual-write) and asserts that, for every
 * dispatch, the bus carries exactly one correlated {@code TaskDispatch}→{@code ToolResult}
 * pair whose target and success flag match the real dispatch outcome. This is
 * the equivalence gate that must pass before M9b (SWITCH) may proceed.
 *
 * <p>If this test ever fails, the bus mirror and the real dispatch have
 * diverged — M9b MUST be blocked until consistency is restored (REQ-02 §8 risk
 * control: "不一致即阻塞切换").
 */
class MessageBusParityTest {

    @Test
    void busMirrorShouldBeEquivalentToActualDispatches() {
        InMemoryMessageBus bus = new InMemoryMessageBus(Runnable::run); // sync → deterministic order
        List<BusMessage> traffic = new CopyOnWriteArrayList<>();
        bus.subscribe(InMemoryMessageBus.WILDCARD_TOPIC, traffic::add);

        StubConversableAgent manager = new StubConversableAgent("Manager", "", true);
        StubConversableAgent ds = new StubConversableAgent("DataScientist", "rows=1", true);
        StubConversableAgent ca = new StubConversableAgent("CodeAssistant", "boom", false);
        StubConversableAgent ta = new StubConversableAgent("ToolAssistant", "tool-ok", true);
        BypassAgentDispatcher dispatcher = new BypassAgentDispatcher(bus, new DirectAgentDispatcher());

        // Run a mixed batch of dispatches.
        record Dispatch(String target, StubConversableAgent worker, boolean expectSuccess) {}
        List<Dispatch> batch = List.of(
                new Dispatch("DataScientist", ds, true),
                new Dispatch("CodeAssistant", ca, false),
                new Dispatch("ToolAssistant", ta, true),
                new Dispatch("DataScientist", ds, true)
        );

        List<String> expectedTargets = new ArrayList<>();
        for (Dispatch d : batch) {
            AgentMessage reply = dispatcher.dispatch(manager, d.worker,
                    AgentMessage.builder().content("task-" + d.target).build(), null).join();
            assertEquals(d.expectSuccess, reply.success(), "real reply success mismatch for " + d.target);
            expectedTargets.add(d.target);
        }

        // Parity assertions over captured bus traffic.
        List<BusMessage.TaskDispatch> dispatches = filter(traffic, BusMessage.TaskDispatch.class);
        List<BusMessage.ToolResult> results = filter(traffic, BusMessage.ToolResult.class);

        assertEquals(batch.size(), dispatches.size(), "one TaskDispatch per dispatch");
        assertEquals(batch.size(), results.size(), "one ToolResult per dispatch");

        for (int i = 0; i < batch.size(); i++) {
            BusMessage.TaskDispatch td = dispatches.get(i);
            BusMessage.ToolResult tr = results.get(i);
            Dispatch expected = batch.get(i);

            assertEquals(expected.target, td.receiverName(), "TaskDispatch target mismatch #" + i);
            assertEquals("Manager", td.senderName(), "TaskDispatch sender must be Manager");
            assertEquals(td.correlationId(), tr.correlationId(),
                    "TaskDispatch/ToolResult correlationId mismatch #" + i);
            assertEquals(expected.expectSuccess, tr.success(),
                    "ToolResult success must match real reply #" + i);
            assertEquals(expected.target, tr.senderName(), "ToolResult sender must be the worker");
            assertEquals("Manager", tr.receiverName(), "ToolResult receiver must be Manager");
        }

        // Ordering: each TaskDispatch must appear before its matching ToolResult.
        for (int i = 0; i < traffic.size() - 1; i++) {
            if (traffic.get(i) instanceof BusMessage.ToolResult) {
                // A ToolResult at i must be preceded by its TaskDispatch somewhere.
                BusMessage.ToolResult tr = (BusMessage.ToolResult) traffic.get(i);
                boolean hasPriorDispatch = traffic.subList(0, i).stream()
                        .anyMatch(m -> m instanceof BusMessage.TaskDispatch td
                                && td.correlationId().equals(tr.correlationId()));
                assertTrue(hasPriorDispatch, "ToolResult without preceding TaskDispatch");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends BusMessage> List<T> filter(List<BusMessage> in, Class<T> type) {
        List<T> out = new ArrayList<>();
        for (BusMessage m : in) if (type.isInstance(m)) out.add((T) m);
        return out;
    }
}
