package com.sql.logic.engine.domain.agentic.core.bus;

import com.sql.logic.engine.domain.agentic.core.AgentMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link BypassAgentDispatcher} (BYPASS / M9a) — verifies:
 * <ul>
 *   <li>execution is identical to the direct path (same reply);</li>
 *   <li>each dispatch is mirrored as a correlated TaskDispatch (before) +
 *       ToolResult (after) on the bus;</li>
 *   <li>bus mirroring is best-effort — a failing handler never breaks dispatch.</li>
 * </ul>
 */
class BypassAgentDispatcherTest {

    private InMemoryMessageBus syncBus() {
        // Synchronous dispatcher → deterministic assertions.
        return new InMemoryMessageBus(Runnable::run);
    }

    @Test
    void shouldExecuteViaDirectPathAndMirrorTaskDispatchThenToolResult() {
        InMemoryMessageBus bus = syncBus();
        List<BusMessage> captured = new CopyOnWriteArrayList<>();
        bus.subscribe(InMemoryMessageBus.WILDCARD_TOPIC, captured::add);

        StubConversableAgent manager = new StubConversableAgent("Manager", "", true);
        StubConversableAgent worker = new StubConversableAgent("DataScientist", "rows=3", true);
        BypassAgentDispatcher dispatcher = new BypassAgentDispatcher(bus, new DirectAgentDispatcher());

        AgentMessage goal = AgentMessage.builder().content("count users").build();
        AgentMessage reply = dispatcher.dispatch(manager, worker, goal, null).join();

        // Execution identical to direct path.
        assertEquals(BusOrchestrationMode.BYPASS, dispatcher.mode());
        assertEquals("rows=3", reply.content());
        assertTrue(reply.success());
        assertEquals(1, worker.replyCount());

        // Mirror: exactly one TaskDispatch then one ToolResult.
        List<BusMessage.TaskDispatch> dispatches = filter(captured, BusMessage.TaskDispatch.class);
        List<BusMessage.ToolResult> results = filter(captured, BusMessage.ToolResult.class);
        assertEquals(1, dispatches.size());
        assertEquals(1, results.size());

        BusMessage.TaskDispatch td = dispatches.get(0);
        BusMessage.ToolResult tr = results.get(0);
        assertEquals("Manager", td.senderName());
        assertEquals("DataScientist", td.receiverName());
        assertEquals(td.correlationId(), tr.correlationId(), "TaskDispatch & ToolResult must share correlationId");
        assertTrue(tr.success());
        assertEquals("DataScientist", tr.senderName());
        assertEquals("Manager", tr.receiverName());
    }

    @Test
    void shouldMirrorFailedReplyAsUnsuccessfulToolResult() {
        InMemoryMessageBus bus = syncBus();
        List<BusMessage> captured = new CopyOnWriteArrayList<>();
        bus.subscribe(InMemoryMessageBus.WILDCARD_TOPIC, captured::add);

        StubConversableAgent manager = new StubConversableAgent("Manager", "", true);
        StubConversableAgent worker = new StubConversableAgent("CodeAssistant", "error", false);
        BypassAgentDispatcher dispatcher = new BypassAgentDispatcher(bus, new DirectAgentDispatcher());

        AgentMessage reply = dispatcher.dispatch(manager, worker,
                AgentMessage.builder().content("q").build(), null).join();

        assertFalse(reply.success());
        BusMessage.ToolResult tr = filter(captured, BusMessage.ToolResult.class).get(0);
        assertFalse(tr.success());
    }

    @Test
    void shouldKeepWorkingWhenBusHandlerThrows() {
        InMemoryMessageBus bus = syncBus();
        // A toxic wildcard handler that throws on every message.
        bus.subscribe(InMemoryMessageBus.WILDCARD_TOPIC, m -> { throw new RuntimeException("boom"); });

        StubConversableAgent manager = new StubConversableAgent("Manager", "", true);
        StubConversableAgent worker = new StubConversableAgent("DataScientist", "ok", true);
        BypassAgentDispatcher dispatcher = new BypassAgentDispatcher(bus, new DirectAgentDispatcher());

        // Dispatch must still succeed — bus faults are isolated (InMemoryMessageBus
        // swallows handler exceptions) and mirroring is best-effort.
        AgentMessage reply = assertDoesNotThrow(() ->
                dispatcher.dispatch(manager, worker, AgentMessage.builder().content("q").build(), null).join());
        assertEquals("ok", reply.content());
    }

    @SuppressWarnings("unchecked")
    private static <T extends BusMessage> List<T> filter(List<BusMessage> in, Class<T> type) {
        List<T> out = new ArrayList<>();
        for (BusMessage m : in) if (type.isInstance(m)) out.add((T) m);
        return out;
    }
}
