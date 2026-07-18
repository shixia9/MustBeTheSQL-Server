package com.sql.logic.engine.domain.agentic.agent;

import com.sql.logic.engine.domain.agentic.core.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DashboardAssistantAgentTest {

    private DashboardAssistantAgent agent;

    @BeforeEach
    void setUp() {
        agent = new DashboardAssistantAgent();
        agent.build();
    }

    @Test
    void shouldHaveCorrectDefaultProfile() {
        assertEquals("DashboardAssistant", agent.name());
        assertEquals("报告生成者", agent.role());
        assertTrue(agent.constraints().stream().anyMatch(c -> c.contains("仅使用已提供的")));
    }

    @Test
    void shouldBuildSystemPromptWithStepResults() {
        List<Map<String, String>> stepResults = List.of(
                Map.of("content", "查询用户数据", "result", "返回 100 行"),
                Map.of("content", "分析趋势", "result", "环比增长 15%")
        );

        String prompt = agent.buildSystemPrompt("generate report", "", "",
                Map.of("stepResults", stepResults));

        assertTrue(prompt.contains("DashboardAssistant"));
        assertTrue(prompt.contains("查询用户数据"));
        assertTrue(prompt.contains("返回 100 行"));
        assertTrue(prompt.contains("环比增长 15%"));
    }

    @Test
    void shouldBuildSystemPromptWithoutStepResults() {
        String prompt = agent.buildSystemPrompt("report", "", "", Map.of());
        assertTrue(prompt.contains("DashboardAssistant"));
    }
}
