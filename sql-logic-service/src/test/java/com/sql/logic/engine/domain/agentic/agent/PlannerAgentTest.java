package com.sql.logic.engine.domain.agentic.agent;

import com.sql.logic.engine.domain.agentic.action.PlanAction;
import com.sql.logic.engine.domain.agentic.core.*;
import com.sql.logic.engine.domain.agentic.plan.InMemoryPlanMemory;
import com.sql.logic.engine.domain.agentic.plan.PlanMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PlannerAgentTest {

    private PlannerAgent agent;
    private PlanMemory planMemory;
    private PlanAction planAction;

    @BeforeEach
    void setUp() {
        agent = new PlannerAgent();
        planMemory = new InMemoryPlanMemory();
        planAction = new PlanAction(planMemory, List.of(
                Map.of("name", "DataScientist", "desc", "SQL generation"),
                Map.of("name", "CodeAssistant", "desc", "Python code execution")
        ));
        agent.bind(List.of(planAction));
        agent.build();
    }

    @Test
    void shouldHaveCorrectDefaultProfile() {
        assertEquals("Planner", agent.name());
        assertEquals("任务规划师", agent.role());
        assertNotNull(agent.goal());
        assertTrue(agent.constraints().stream().anyMatch(c -> c.contains("直接生成最终计划")));
    }

    @Test
    void shouldParsePlanInputsFromJsonArray() {
        String json = """
                [
                  {"serial_number": 1, "agent": "DataScientist", "content": "查询销售数据", "rely": ""},
                  {"serial_number": 2, "agent": "CodeAssistant", "content": "分析趋势", "rely": "1"}
                ]""";

        List<PlanAction.PlanInput> inputs = planAction.parsePlanInputs(json);
        assertEquals(2, inputs.size());
        assertEquals(1, inputs.get(0).serial_number);
        assertEquals("DataScientist", inputs.get(0).agent);
        assertEquals("1", inputs.get(1).rely);
    }

    @Test
    void shouldParsePlanInputsFromMarkdownWrappedJson() {
        String json = """
                ```json
                [{"serial_number": 1, "agent": "DataScientist", "content": "Query", "rely": ""}]
                ```
                Some additional text""";

        List<PlanAction.PlanInput> inputs = planAction.parsePlanInputs(json);
        assertEquals(1, inputs.size());
        assertEquals("DataScientist", inputs.get(0).agent);
    }

    @Test
    void shouldReturnEmptyListForInvalidJson() {
        List<PlanAction.PlanInput> inputs = planAction.parsePlanInputs("not json at all");
        assertTrue(inputs.isEmpty());
    }

    @Test
    void shouldBuildSystemPromptWithAgentDescriptions() {
        String prompt = agent.buildSystemPrompt("test", "", "",
                Map.of("agentDescriptions", List.of("DataScientist: SQL", "CodeAssistant: Python")));
        assertTrue(prompt.contains("Planner"));
        assertTrue(prompt.contains("DataScientist: SQL"));
    }

    @Test
    void actShouldDelegateToPlanAction() throws Exception {
        AgentMessage msg = AgentMessage.builder()
                .content("""
                    [{"serial_number": 1, "agent": "DataScientist", "content": "Query users", "rely": ""}]""")
                .putContext("threadId", "test-thread")
                .build();

        ActionOutput result = agent.act(msg, null).get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertTrue(result.isExeSuccess());
        assertTrue(result.content().contains("计划已生成"));

        // Plan should be saved to PlanMemory
        assertFalse(planMemory.getByConvId("test-thread").isEmpty());
    }
}
