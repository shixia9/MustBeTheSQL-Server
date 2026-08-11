package com.sql.logic.engine.domain.agentic.core.bus;

import com.sql.logic.engine.domain.agentic.agent.ManagerAgent;
import com.sql.logic.engine.domain.agentic.core.ActionOutput;
import com.sql.logic.engine.domain.agentic.core.AgentMessage;
import com.sql.logic.engine.domain.agentic.plan.InMemoryPlanMemory;
import com.sql.logic.engine.domain.agentic.plan.PlanMemory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * REQ-02 AC-3 / AC-4 — ManagerAgent end-to-end across all three bus modes.
 *
 * <p>Drives the real {@link ManagerAgent#act} tool-invocation path (which routes
 * straight to a worker via {@code dispatchToWorker}) in OFF, BYPASS and SWITCH
 * modes and asserts identical orchestration outcomes — the core zero-regression
 * guarantee. BYPASS/SWITCH additionally assert that business traffic flows over
 * the bus.
 *
 * <p>Uses the tool-invocation shortcut to keep the test LLM-free: it exercises
 * the same {@code dispatchToWorker} chokepoint as the full Planner→Worker→Dashboard
 * pipeline without requiring a ComplexityRouter or live LLM.
 */
class ManagerAgentBusIntegrationTest {

    private BusWorkerEndpointRegistrar registrar;
    private BusAgentDispatcher switchDispatcher;

    @AfterEach
    void tearDown() {
        if (registrar != null) registrar.stopAll();
        if (switchDispatcher != null) switchDispatcher.shutdown();
    }

    @Test
    void offModeShouldDispatchDirectlyAndSucceed() {
        Result r = run(BusOrchestrationMode.OFF);
        assertEquals("tool-result", r.output.content());
        assertTrue(r.output.isExeSuccess());
        assertEquals("tool_invocation", r.output.data().get("route"));
        // OFF mode → no bus traffic.
        assertTrue(r.traffic.isEmpty());
    }

    @Test
    void bypassModeShouldProduceIdenticalResultPlusBusMirror() {
        Result r = run(BusOrchestrationMode.BYPASS);
        // Identical outcome to OFF mode.
        assertEquals("tool-result", r.output.content());
        assertTrue(r.output.isExeSuccess());
        assertEquals("tool_invocation", r.output.data().get("route"));
        // Bus mirror present: one TaskDispatch + one ToolResult, correlated.
        var dispatches = r.traffic.stream().filter(BusMessage.TaskDispatch.class::isInstance).toList();
        var results = r.traffic.stream().filter(BusMessage.ToolResult.class::isInstance).toList();
        assertEquals(1, dispatches.size());
        assertEquals(1, results.size());
        BusMessage.TaskDispatch td = (BusMessage.TaskDispatch) dispatches.get(0);
        BusMessage.ToolResult tr = (BusMessage.ToolResult) results.get(0);
        assertEquals("ToolAssistant", td.receiverName());
        assertEquals(td.correlationId(), tr.correlationId());
        assertTrue(tr.success());
    }

    @Test
    void switchModeShouldDispatchOverBusAndProduceIdenticalResult() {
        Result r = run(BusOrchestrationMode.SWITCH);
        // Identical outcome to OFF mode (the bus is now the channel of record).
        assertEquals("tool-result", r.output.content());
        assertTrue(r.output.isExeSuccess());
        assertEquals("tool_invocation", r.output.data().get("route"));
        // Business traffic travelled over the bus.
        var dispatches = r.traffic.stream().filter(BusMessage.TaskDispatch.class::isInstance).toList();
        var results = r.traffic.stream().filter(BusMessage.ToolResult.class::isInstance).toList();
        assertEquals(1, dispatches.size());
        assertEquals(1, results.size());
        assertTrue(((BusMessage.ToolResult) results.get(0)).success());
    }

    // ------------------------------------------------------------------
    //  Harness
    // ------------------------------------------------------------------

    private record Result(ActionOutput output, List<BusMessage> traffic) {}

    private Result run(BusOrchestrationMode mode) {
        InMemoryMessageBus bus = new InMemoryMessageBus();
        List<BusMessage> traffic = new CopyOnWriteArrayList<>();
        bus.subscribe(InMemoryMessageBus.WILDCARD_TOPIC, traffic::add);

        PlanMemory planMemory = new InMemoryPlanMemory();
        StubConversableAgent toolAssistant = new StubConversableAgent("ToolAssistant", "tool-result", true);

        ManagerAgent manager = new ManagerAgent();
        manager.setPlanMemory(planMemory);

        AgentDispatcher dispatcher = switch (mode) {
            case OFF -> new DirectAgentDispatcher();
            case BYPASS -> new BypassAgentDispatcher(bus, new DirectAgentDispatcher());
            case SWITCH -> {
                // Register the worker endpoint BEFORE dispatch so the bus has a live worker.
                registrar = new BusWorkerEndpointRegistrar(bus, "Manager");
                registrar.register(toolAssistant);
                switchDispatcher = new BusAgentDispatcher(bus, "Manager", 30L);
                yield switchDispatcher;
            }
        };
        manager.setDispatcher(dispatcher);
        manager.hire(toolAssistant);
        manager.build();

        AgentMessage msg = AgentMessage.builder()
                .content("run the search tool")
                .putContext("threadId", UUID.randomUUID().toString())
                .putContext("toolInvocation", Map.of("toolName", "search"))
                .build();

        ActionOutput out = manager.act(msg, null).join();
        return new Result(out, traffic);
    }
}
