package com.sql.logic.engine.domain.agentic.agent;

import com.sql.logic.engine.domain.agentic.core.*;
import com.sql.logic.engine.domain.agentic.plan.InMemoryPlanMemory;
import com.sql.logic.engine.domain.agentic.plan.PlanMemory;
import com.sql.logic.engine.domain.agentic.plan.PlanStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ManagerAgentTest {

    private ManagerAgent manager;
    private PlanMemory planMemory;
    private StubAgent workerAgent;

    /**
     * A stub agent that always succeeds.
     */
    static class StubAgent extends ConversableAgent {
        private final String agentName;

        StubAgent(String name, String role, String goal) {
            this.agentName = name;
            this.profile = com.sql.logic.engine.domain.agentic.profile.ProfileConfig.builder()
                    .name(name).role(role).goal(goal).build();
        }

        @Override
        public String name() { return agentName; }

        @Override
        public CompletableFuture<AgentMessage> generateReply(
                AgentMessage msg, Agent sender,
                List<AgentMessage> relyMessages,
                List<AgentMessage> historicalDialogues) {
            return CompletableFuture.completedFuture(
                    AgentMessage.builder()
                            .content("Result of: " + msg.content())
                            .success(true)
                            .actionReport(ActionOutput.success("Completed: " + msg.content()))
                            .build()
            );
        }

        @Override
        protected String buildSystemPrompt(String o, String m, String r, Map<String, Object> c) {
            return "System: " + agentName;
        }

        @Override
        protected String buildUserPrompt(String o, String m, String r, Map<String, Object> c) {
            return o;
        }
    }

    /**
     * A stub planner that generates a fixed plan.
     */
    static class StubPlannerAgent extends PlannerAgent {
        @Override
        public CompletableFuture<AgentMessage> generateReply(
                AgentMessage msg, Agent sender,
                List<AgentMessage> relyMessages,
                List<AgentMessage> historicalDialogues) {
            return CompletableFuture.completedFuture(
                    AgentMessage.builder()
                            .content("plan generated")
                            .success(true)
                            .actionReport(ActionOutput.success("plan generated",
                                    Map.of("steps", List.of(
                                            new PlanStep(1, "TestAgent", "Step 1", ""),
                                            new PlanStep(2, "TestAgent", "Step 2", "1")
                                    ), "stepCount", 2)))
                            .build()
            );
        }
    }

    @BeforeEach
    void setUp() {
        manager = new ManagerAgent();
        planMemory = new InMemoryPlanMemory();
        workerAgent = new StubAgent("TestAgent", "Tester", "Testing");

        manager.setPlanMemory(planMemory);
        manager.setPlannerAgent(new StubPlannerAgent());
        manager.hire(workerAgent);
        manager.build();
    }

    @Test
    void shouldHaveCorrectDefaultProfile() {
        assertEquals("Manager", manager.name());
        assertEquals("编排管理者", manager.role());
    }

    @Test
    void shouldSelectSpeakerByAgentName() {
        PlanStep step = new PlanStep(1, "TestAgent", "test", "");
        Agent speaker = manager.selectSpeaker(step);
        assertNotNull(speaker);
        assertEquals("TestAgent", speaker.name());
    }

    @Test
    void shouldFallbackToFirstAgentForNonexistentAgent() {
        PlanStep step = new PlanStep(1, "NonExistent", "test", "");
        Agent speaker = manager.selectSpeaker(step);
        // Falls back to first available agent
        assertNotNull(speaker);
        assertEquals("TestAgent", speaker.name());
    }

    @Test
    void shouldHireAgents() {
        assertEquals(1, manager.getAgents().size());
        StubAgent another = new StubAgent("AnotherAgent", "Role", "Goal");
        manager.hire(another);
        assertEquals(2, manager.getAgents().size());
    }

    @Test
    void processRelyMessagesShouldReturnEmptyWhenNoRely() {
        PlanStep step = new PlanStep(1, "TestAgent", "test", "");
        List<AgentMessage> relyMessages = manager.processRelyMessages("conv-1", step);
        assertTrue(relyMessages.isEmpty());
    }

    @Test
    void processRelyMessagesShouldBuildContextFromDependencySteps() {
        PlanStep step1 = new PlanStep(1, "DS", "Step 1 question", "");
        step1.setResult("Step 1 answer");
        planMemory.savePlan("conv-1", List.of(step1));

        PlanStep step2 = new PlanStep(2, "CA", "Step 2", "1");
        List<AgentMessage> relyMessages = manager.processRelyMessages("conv-1", step2);

        assertEquals(2, relyMessages.size());
        assertTrue(relyMessages.get(0).content().contains("Step 1 question"));
        assertTrue(relyMessages.get(1).content().contains("Step 1 answer"));
    }

    @Test
    void processRelyMessagesShouldHandleMultipleDependencies() {
        PlanStep step1 = new PlanStep(1, "DS", "Q1", "");
        step1.setResult("R1");
        PlanStep step3 = new PlanStep(3, "DS", "Q3", "");
        step3.setResult("R3");
        planMemory.savePlan("conv-1", List.of(step1, new PlanStep(2, "DS", "Q2", ""), step3));

        PlanStep step = new PlanStep(4, "CA", "Final", "1,3");
        List<AgentMessage> relyMessages = manager.processRelyMessages("conv-1", step);

        assertEquals(4, relyMessages.size()); // Q1, R1, Q3, R3
    }
}
