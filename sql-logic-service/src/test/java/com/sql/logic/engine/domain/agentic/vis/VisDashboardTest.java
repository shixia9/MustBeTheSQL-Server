package com.sql.logic.engine.domain.agentic.vis;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VisDashboardTest {

    private VisDashboard visDashboard;

    @BeforeEach
    void setUp() {
        visDashboard = new VisDashboard();
    }

    @Test
    void shouldHaveCorrectTag() {
        assertEquals("vis-dashboard", visDashboard.visTag());
    }

    @Test
    void shouldBuildParamsFromChartItems() {
        ChartItem item1 = ChartItem.of("Sales", "response_bar_chart",
                "SELECT * FROM sales", "Sales analysis").withData(
                List.of(Map.of("region", "East", "amount", 1000L)));
        ChartItem item2 = ChartItem.of("Users", "response_line_chart",
                "SELECT * FROM users", "User trend").withData(
                List.of(Map.of("date", "2024-01-01", "count", 50L)));

        List<ChartItem> charts = List.of(item1, item2);
        Map<String, Object> params = visDashboard.buildParams(charts, "My Dashboard");

        assertEquals(2, params.get("chart_count"));
        assertEquals("My Dashboard", params.get("title"));
        assertEquals("default", params.get("display_strategy"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) params.get("data");
        assertEquals(2, data.size());
        assertEquals("Sales", data.get(0).get("title"));
        assertEquals("response_bar_chart", data.get(0).get("type"));
        assertNotNull(data.get(0).get("data"));
    }

    @Test
    void shouldHandleEmptyCharts() {
        Map<String, Object> params = visDashboard.buildParams(List.of(), "Empty Dashboard");

        assertEquals(0, params.get("chart_count"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) params.get("data");
        assertTrue(data.isEmpty());
    }

    @Test
    void shouldIncludeErrorsInParams() {
        ChartItem failed = ChartItem.of("Bad Query", "response_table",
                "INVALID SQL", "").withError("Syntax error");

        Map<String, Object> params = visDashboard.buildParams(List.of(failed), "Dashboard");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) params.get("data");
        assertEquals(1, data.size());
        assertEquals("Syntax error", data.get(0).get("error"));
    }

    @Test
    void renderShouldWrapWithMarkdownFence() {
        ChartItem item = ChartItem.of("Test", "response_table", "SELECT 1", "");
        String output = visDashboard.render(List.of(item), "Dashboard");

        assertNotNull(output);
        assertTrue(output.contains("```vis-dashboard"));
        assertTrue(output.contains("```"));
    }

    @Test
    void toJsonShouldReturnValidJson() {
        DashboardData dd = DashboardData.of(
                List.of(ChartItem.of("T1", "response_table", "SELECT 1", "")),
                "Test Dashboard"
        );

        String json = visDashboard.toJson(dd);
        assertNotNull(json);
        assertTrue(json.contains("chartCount"));
        assertTrue(json.contains("Test Dashboard"));
    }
}
