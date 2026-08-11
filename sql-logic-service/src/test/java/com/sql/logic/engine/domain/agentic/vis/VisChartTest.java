package com.sql.logic.engine.domain.agentic.vis;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VisChartTest {

    private VisChart visChart;

    @BeforeEach
    void setUp() {
        visChart = new VisChart();
    }

    @Test
    void shouldHaveCorrectTag() {
        assertEquals("vis-db-chart", visChart.visTag());
    }

    @Test
    void shouldBuildParamsWithAllFields() {
        List<Map<String, Object>> data = List.of(
                Map.of("city", "Beijing", "count", 100L),
                Map.of("city", "Shanghai", "count", 200L)
        );
        List<Map<String, String>> columns = List.of(
                Map.of("name", "city"), Map.of("name", "count")
        );

        Map<String, Object> params = visChart.buildParams(
                "SELECT city, COUNT(*) FROM t GROUP BY city",
                "response_bar_chart",
                "City Distribution",
                "Shows city count distribution",
                data,
                columns
        );

        assertEquals("SELECT city, COUNT(*) FROM t GROUP BY city", params.get("sql"));
        assertEquals("response_bar_chart", params.get("type"));
        assertEquals("City Distribution", params.get("title"));
        assertEquals("Shows city count distribution", params.get("describe"));
        assertEquals(data, params.get("data"));
        assertEquals(columns, params.get("columns"));
    }

    @Test
    void shouldHandleNullsGracefully() {
        Map<String, Object> params = visChart.buildParams(
                null, null, null, null, null, null
        );

        assertEquals("", params.get("sql"));
        assertEquals("response_table", params.get("type"));
        assertEquals("", params.get("title"));
        assertEquals(List.of(), params.get("data"));
    }

    @Test
    void shouldWrapOutputWithMarkdownFence() {
        String output = visChart.render(
                "SELECT 1", "response_table", "Test", "desc",
                List.of(), List.of()
        );

        assertNotNull(output);
        assertTrue(output.contains("```vis-db-chart"));
        assertTrue(output.contains("```"));
    }

    @Test
    void displayShouldReturnValidJson() {
        Map<String, Object> params = Map.of("type", "response_table");
        String output = visChart.display(params);

        assertNotNull(output);
        assertTrue(output.contains("vis-db-chart"));
        assertTrue(output.contains("response_table"));
    }
}
