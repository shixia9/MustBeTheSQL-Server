package com.sql.logic.engine.domain.agentic.vis;

/**
 * Chart types supported by the visualization system.
 * Maps to frontend recharts components with DB-GPT-compatible naming.
 */
public enum ChartType {
    RESPONSE_TABLE("response_table", "Table"),
    RESPONSE_BAR_CHART("response_bar_chart", "Bar Chart"),
    RESPONSE_LINE_CHART("response_line_chart", "Line Chart"),
    RESPONSE_PIE_CHART("response_pie_chart", "Pie Chart"),
    RESPONSE_SCATTER_CHART("response_scatter_chart", "Scatter Chart"),
    RESPONSE_AREA_CHART("response_area_chart", "Area Chart"),
    RESPONSE_HEATMAP("response_heatmap", "Heatmap"),
    RESPONSE_DONUT_CHART("response_donut_chart", "Donut Chart"),
    RESPONSE_BUBBLE_CHART("response_bubble_chart", "Bubble Chart"),
    RESPONSE_INDICATOR("response_indicator", "Indicator Value");

    private final String code;
    private final String displayName;

    ChartType(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public String code() { return code; }
    public String displayName() { return displayName; }

    public static ChartType fromCode(String code) {
        for (ChartType t : values()) {
            if (t.code.equalsIgnoreCase(code)) return t;
        }
        return RESPONSE_TABLE; // safe default
    }

    /**
     * Build the prompt fragment that instructs the LLM how to choose chart types.
     * Mirrors DB-GPT's {@code default_chart_type_prompt()}.
     */
    public static String buildChartTypePrompt() {
        return """
            You MUST choose one of the following chart types for data visualization.
            Output the exact type code string (e.g. "response_bar_chart") in the display_type field.

            Available chart types and when to use them:
            - response_line_chart: Comparative trend analysis over time, time-series data
            - response_bar_chart: Comparing values across categories, ranking
            - response_pie_chart: Proportion and distribution (use when categories <= 6)
            - response_donut_chart: Hierarchical structure, category proportion display
            - response_scatter_chart: Exploring relationships between two numeric variables
            - response_bubble_chart: Multi-variable relationships, highlighting outliers
            - response_area_chart: Time series with volume emphasis, multi-group comparison
            - response_heatmap: Time-series patterns, large-scale classified data distribution
            - response_table: Many display columns, non-numeric columns, or when unsure
            - response_indicator: Single key metric value (count, sum, average)

            Chart type selection guidelines:
            1. For time-series data with trends → response_line_chart or response_area_chart
            2. For category comparison (top N) → response_bar_chart
            3. For proportion/distribution → response_pie_chart or response_donut_chart
            4. For correlation/relationship → response_scatter_chart
            5. For single metric display → response_indicator
            6. If you don't know what to output, output "response_table"
            """;
    }
}
