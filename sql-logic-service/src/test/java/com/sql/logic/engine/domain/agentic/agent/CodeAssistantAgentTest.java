package com.sql.logic.engine.domain.agentic.agent;

import com.sql.logic.engine.domain.agentic.core.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CodeAssistantAgentTest {

    private CodeAssistantAgent agent;

    @BeforeEach
    void setUp() {
        agent = new CodeAssistantAgent();
        agent.build();
    }

    @Test
    void shouldHaveCorrectDefaultProfile() {
        assertEquals("CodeAssistant", agent.name());
        assertEquals("代码工程师", agent.role());
        assertTrue(agent.goal().contains("Python"));
        assertTrue(agent.constraints().stream().anyMatch(c -> c.contains("Docker")));
    }

    @Test
    void actShouldFailWithoutActions() throws Exception {
        ActionOutput result = agent.act(
                AgentMessage.user("test"), null
        ).get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertFalse(result.isExeSuccess());
        assertTrue(result.content().contains("No actions"));
    }

    @Test
    void verifyShouldFailWhenNoActionOutput() throws Exception {
        AgentMessage msg = AgentMessage.ai("test");
        VerifyResult result = agent.verify(msg, null).get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertFalse(result.passed());
    }

    @Test
    void verifyShouldFailOnEmptyOutput() throws Exception {
        AgentMessage msg = AgentMessage.builder()
                .actionReport(ActionOutput.success(""))
                .build();
        VerifyResult result = agent.verify(msg, null).get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertFalse(result.passed());
    }

    @Test
    void verifyShouldPassOnValidOutput() throws Exception {
        AgentMessage msg = AgentMessage.builder()
                .actionReport(ActionOutput.success("import pandas as pd; print('ok')"))
                .build();
        VerifyResult result = agent.verify(msg, null).get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertTrue(result.passed());
    }

    @Test
    void shouldBuildSystemPrompt() {
        String prompt = agent.buildSystemPrompt("analyze data", "", "schema info", java.util.Map.of());
        assertTrue(prompt.contains("CodeAssistant"));
        assertTrue(prompt.contains("schema info"));
    }

    @Test
    void shouldBuildUserPrompt() {
        String prompt = agent.buildUserPrompt("query", "", "", java.util.Map.of());
        assertTrue(prompt.contains("query"));
    }
}
