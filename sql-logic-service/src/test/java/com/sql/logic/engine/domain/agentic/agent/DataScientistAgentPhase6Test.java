package com.sql.logic.engine.domain.agentic.agent;

import com.sql.logic.engine.domain.agentic.vis.ChartType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DataScientistAgentPhase6Test {

    private DataScientistAgent agent;

    @BeforeEach
    void setUp() {
        agent = new DataScientistAgent();
    }

    @Test
    void systemPromptShouldIncludeChartTypePrompt() {
        String prompt = agent.buildSystemPrompt(
                "test query", null, "schema context", Map.of()
        );

        assertNotNull(prompt);
        assertTrue(prompt.contains("display_type"),
                "System prompt should include display_type chart context");
        assertTrue(prompt.contains("response_bar_chart"),
                "System prompt should include bar chart type");
        assertTrue(prompt.contains("response_line_chart"),
                "System prompt should include line chart type");
        assertTrue(prompt.contains("response_table"),
                "System prompt should include table type");
    }

    @Test
    void systemPromptShouldIncludeJsonOutputFormat() {
        String prompt = agent.buildSystemPrompt(
                "test", null, null, Map.of()
        );

        assertTrue(prompt.contains("JSON object"),
                "System prompt should instruct JSON output format");
        assertTrue(prompt.contains("display_type"),
                "System prompt should mention display_type field");
        assertTrue(prompt.contains("sql"),
                "System prompt should mention sql field");
        assertTrue(prompt.contains("thought"),
                "System prompt should mention thought field");
    }

    @Test
    void chartTypePromptShouldBeComplete() {
        String chartPrompt = ChartType.buildChartTypePrompt();

        // All 10 types should be present
        for (ChartType t : ChartType.values()) {
            assertTrue(chartPrompt.contains(t.code()),
                    "Chart prompt should mention " + t.code());
        }
    }

    @Test
    void systemPromptShouldIncludeResourceContext() {
        String prompt = agent.buildSystemPrompt(
                "test", null, "CREATE TABLE users (id INT, name VARCHAR)", Map.of()
        );

        assertTrue(prompt.contains("Available Resources"));
        assertTrue(prompt.contains("CREATE TABLE"));
    }

    @Test
    void systemPromptShouldIncludeMemoryContext() {
        String prompt = agent.buildSystemPrompt(
                "test", "Previous query: SELECT * FROM users", null, Map.of()
        );

        assertTrue(prompt.contains("Relevant Context"));
        assertTrue(prompt.contains("Previous query"));
    }

    @Test
    void multiCandidateModeShouldAddHint() {
        agent.setMultiCandidateMode(true);
        String prompt = agent.buildSystemPrompt(
                "test", null, null, Map.of()
        );

        assertTrue(prompt.contains("Multi-Candidate Mode"));
        assertTrue(prompt.contains("competitive mode"));
    }

    @Test
    void defaultModeShouldNotHaveMultiCandidateHint() {
        String prompt = agent.buildSystemPrompt(
                "test", null, null, Map.of()
        );

        assertFalse(prompt.contains("Multi-Candidate Mode"));
    }
}
