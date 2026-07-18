package com.sql.logic.engine.domain.agentic.plan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlanStepTest {

    @Test
    void shouldCreatePlanStepWithDefaultStatus() {
        PlanStep step = new PlanStep(1, "DataScientist", "查询用户数据", "");
        assertEquals(1, step.getSerialNumber());
        assertEquals("DataScientist", step.getAgent());
        assertEquals("查询用户数据", step.getContent());
        assertEquals("", step.getRely());
        assertEquals(PlanStatus.TODO, step.getStatus());
        assertEquals(0, step.getRetryTimes());
        assertEquals(3, step.getMaxRetryTimes());
    }

    @Test
    void shouldUpdateStatus() {
        PlanStep step = new PlanStep(1, "DataScientist", "test", "");
        step.setStatus(PlanStatus.COMPLETED);
        assertEquals(PlanStatus.COMPLETED, step.getStatus());
    }

    @Test
    void shouldUpdateResult() {
        PlanStep step = new PlanStep(1, "DataScientist", "test", "");
        step.setResult("Query returned 5 rows");
        assertEquals("Query returned 5 rows", step.getResult());
    }

    @Test
    void shouldSetDependencyRely() {
        PlanStep step = new PlanStep(2, "CodeAssistant", "分析数据", "1");
        assertEquals("1", step.getRely());
    }

    @Test
    void shouldTrackRetryCount() {
        PlanStep step = new PlanStep(1, "DataScientist", "test", "");
        step.setRetryTimes(2);
        assertEquals(2, step.getRetryTimes());
    }
}
