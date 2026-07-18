package com.sql.logic.engine.domain.agentic.plan;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlanMemoryTest {

    private InMemoryPlanMemory planMemory;

    @BeforeEach
    void setUp() {
        planMemory = new InMemoryPlanMemory();
    }

    @Test
    void shouldSaveAndRetrievePlans() {
        List<PlanStep> steps = List.of(
                new PlanStep(1, "DataScientist", "查询数据", ""),
                new PlanStep(2, "CodeAssistant", "分析数据", "1")
        );
        planMemory.savePlan("conv-1", steps);

        List<PlanStep> retrieved = planMemory.getByConvId("conv-1");
        assertEquals(2, retrieved.size());
        assertEquals("DataScientist", retrieved.get(0).getAgent());
    }

    @Test
    void todoPlansShouldIncludeTodoAndFailed() {
        PlanStep step1 = new PlanStep(1, "DS", "task1", "");
        PlanStep step2 = new PlanStep(2, "CA", "task2", "");
        step2.setStatus(PlanStatus.COMPLETED);
        PlanStep step3 = new PlanStep(3, "DS", "task3", "");
        step3.setStatus(PlanStatus.FAILED);

        planMemory.savePlan("conv-1", List.of(step1, step2, step3));

        List<PlanStep> todo = planMemory.getTodoPlans("conv-1");
        assertEquals(2, todo.size()); // step1 (TODO) + step3 (FAILED), but NOT step2 (COMPLETED)
        assertTrue(todo.stream().anyMatch(p -> p.getSerialNumber() == 1));
        assertTrue(todo.stream().anyMatch(p -> p.getSerialNumber() == 3));
    }

    @Test
    void shouldCompleteTask() {
        PlanStep step = new PlanStep(1, "DS", "task", "");
        planMemory.savePlan("conv-1", List.of(step));
        planMemory.completeTask("conv-1", 1, "done");

        assertEquals(PlanStatus.COMPLETED, planMemory.getByConvId("conv-1").get(0).getStatus());
        assertEquals("done", planMemory.getByConvId("conv-1").get(0).getResult());
    }

    @Test
    void shouldUpdateTask() {
        PlanStep step = new PlanStep(1, "DS", "task", "");
        planMemory.savePlan("conv-1", List.of(step));
        planMemory.updateTask("conv-1", 1, PlanStatus.FAILED, 2, "NewAgent", "error result");

        PlanStep updated = planMemory.getByConvId("conv-1").get(0);
        assertEquals(PlanStatus.FAILED, updated.getStatus());
        assertEquals(2, updated.getRetryTimes());
        assertEquals("NewAgent", updated.getAgent());
        assertEquals("error result", updated.getResult());
    }

    @Test
    void shouldFindByConvIdAndNum() {
        PlanStep step1 = new PlanStep(1, "DS", "task1", "");
        PlanStep step2 = new PlanStep(2, "CA", "task2", "1");
        planMemory.savePlan("conv-1", List.of(step1, step2));

        List<PlanStep> rely = planMemory.getByConvIdAndNum("conv-1", List.of(1));
        assertEquals(1, rely.size());
        assertEquals("task1", rely.get(0).getContent());
    }

    @Test
    void shouldRemoveByConvId() {
        planMemory.savePlan("conv-1", List.of(new PlanStep(1, "DS", "task", "")));
        assertFalse(planMemory.getByConvId("conv-1").isEmpty());

        planMemory.removeByConvId("conv-1");
        assertTrue(planMemory.getByConvId("conv-1").isEmpty());
    }

    @Test
    void emptyConvIdShouldReturnEmptyList() {
        assertTrue(planMemory.getByConvId("nonexistent").isEmpty());
        assertTrue(planMemory.getTodoPlans("nonexistent").isEmpty());
    }
}
