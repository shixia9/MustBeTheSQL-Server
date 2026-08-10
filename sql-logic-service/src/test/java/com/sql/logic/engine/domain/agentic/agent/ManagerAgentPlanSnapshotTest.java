package com.sql.logic.engine.domain.agentic.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sql.logic.engine.domain.agent.core.AgentEventSinkRegistry;
import com.sql.logic.engine.domain.agent.core.AgentSseCodec;
import com.sql.logic.engine.domain.agentic.plan.InMemoryPlanMemory;
import com.sql.logic.engine.domain.agentic.plan.PlanStep;
import com.sql.logic.engine.domain.agentic.plan.PlanStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ManagerAgent#emitPlanSnapshot(String)} — verifies the
 * {@code PLAN_UPDATED} SSE event carries a correct full plan snapshot at every
 * plan-state transition, including step counts, status names, and result
 * truncation. Also verifies no event is emitted (and no exception thrown) when
 * the plan is empty.
 */
class ManagerAgentPlanSnapshotTest {

    private ManagerAgent managerAgent;
    private AgentEventSinkRegistry registry;
    private InMemoryPlanMemory planMemory;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        managerAgent = new ManagerAgent();
        planMemory = new InMemoryPlanMemory();
        registry = new AgentEventSinkRegistry();
        managerAgent.setPlanMemory(planMemory);
        managerAgent.setEventSinkRegistry(registry);
        managerAgent.setCodec(new AgentSseCodec(mapper));
    }

    @Test
    void shouldEmitPlanSnapshotWithCorrectCounts() throws Exception {
        PlanStep step1 = new PlanStep(1, "DataScientist", "query 1", "");
        // step1 defaults to PlanStatus.TODO
        PlanStep step2 = new PlanStep(2, "CodeAssistant", "query 2", "1");
        step2.setStatus(PlanStatus.RUNNING);
        PlanStep step3 = new PlanStep(3, "DashboardAssistant", "query 3", "2");
        step3.setStatus(PlanStatus.COMPLETED);
        step3.setResult("done");
        planMemory.savePlan("t1", List.of(step1, step2, step3));

        List<String> collected = new CopyOnWriteArrayList<>();
        Sinks.Many<String> sink = registry.register("t1");
        sink.asFlux().subscribe(collected::add);

        managerAgent.emitPlanSnapshot("t1");

        assertEquals(1, collected.size(), "exactly one PLAN_UPDATED event expected");
        JsonNode event = mapper.readTree(collected.get(0));
        assertEquals("PLAN", event.path("nodeName").asText());
        assertEquals("PLAN_UPDATED", event.path("outputType").asText());
        assertEquals("STATUS", event.path("messageType").asText());
        JsonNode data = event.path("data");
        assertEquals(3, data.path("totalSteps").asInt());
        assertEquals(1, data.path("completedSteps").asInt());
        assertEquals(0, data.path("failedSteps").asInt());
        assertEquals("TODO", data.path("steps").get(0).path("status").asText());
    }

    @Test
    void shouldTruncateLongResult() throws Exception {
        String longResult = "a".repeat(600);
        PlanStep step = new PlanStep(1, "DataScientist", "query", "");
        step.setStatus(PlanStatus.COMPLETED);
        step.setResult(longResult);
        planMemory.savePlan("t2", List.of(step));

        List<String> collected = new CopyOnWriteArrayList<>();
        Sinks.Many<String> sink = registry.register("t2");
        sink.asFlux().subscribe(collected::add);

        managerAgent.emitPlanSnapshot("t2");

        assertEquals(1, collected.size());
        JsonNode event = mapper.readTree(collected.get(0));
        String result = event.path("data").path("steps").get(0).path("result").asText();
        assertEquals(501, result.length(), "result should be 500 chars + ellipsis");
        assertTrue(result.endsWith("…"));
    }

    @Test
    void shouldNotEmitWhenPlanEmpty() {
        List<String> collected = new CopyOnWriteArrayList<>();
        Sinks.Many<String> sink = registry.register("t3");
        sink.asFlux().subscribe(collected::add);

        // No savePlan called — planMemory is empty for "t3"
        assertDoesNotThrow(() -> managerAgent.emitPlanSnapshot("t3"));
        assertTrue(collected.isEmpty(), "no event should be emitted when plan is empty");
    }
}
