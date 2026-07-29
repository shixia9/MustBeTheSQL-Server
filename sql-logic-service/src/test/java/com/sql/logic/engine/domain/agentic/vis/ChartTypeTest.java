package com.sql.logic.engine.domain.agentic.vis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChartTypeTest {

    @Test
    void shouldHaveAllNineTypes() {
        assertEquals(10, ChartType.values().length);
    }

    @Test
    void shouldResolveFromCode() {
        assertEquals(ChartType.RESPONSE_BAR_CHART, ChartType.fromCode("response_bar_chart"));
        assertEquals(ChartType.RESPONSE_LINE_CHART, ChartType.fromCode("response_line_chart"));
        assertEquals(ChartType.RESPONSE_PIE_CHART, ChartType.fromCode("response_pie_chart"));
        assertEquals(ChartType.RESPONSE_TABLE, ChartType.fromCode("response_table"));
    }

    @Test
    void shouldFallbackToTableForUnknownCode() {
        assertEquals(ChartType.RESPONSE_TABLE, ChartType.fromCode("unknown_type"));
        assertEquals(ChartType.RESPONSE_TABLE, ChartType.fromCode(""));
    }

    @Test
    void shouldBeCaseInsensitive() {
        assertEquals(ChartType.RESPONSE_BAR_CHART, ChartType.fromCode("RESPONSE_BAR_CHART"));
        assertEquals(ChartType.RESPONSE_TABLE, ChartType.fromCode("Response_Table"));
    }

    @Test
    void shouldHaveCorrectDisplayNames() {
        assertEquals("Bar Chart", ChartType.RESPONSE_BAR_CHART.displayName());
        assertEquals("Line Chart", ChartType.RESPONSE_LINE_CHART.displayName());
        assertEquals("Table", ChartType.RESPONSE_TABLE.displayName());
        assertEquals("Indicator Value", ChartType.RESPONSE_INDICATOR.displayName());
    }

    @Test
    void shouldBuildChartTypePrompt() {
        String prompt = ChartType.buildChartTypePrompt();
        assertNotNull(prompt);
        assertTrue(prompt.contains("response_line_chart"));
        assertTrue(prompt.contains("response_bar_chart"));
        assertTrue(prompt.contains("response_pie_chart"));
        assertTrue(prompt.contains("response_table"));
        assertTrue(prompt.contains("display_type"));
    }

    @Test
    void eachCodeShouldBeUnique() {
        var codes = new java.util.HashSet<String>();
        for (ChartType t : ChartType.values()) {
            assertTrue(codes.add(t.code()), "Duplicate code: " + t.code());
        }
    }
}
