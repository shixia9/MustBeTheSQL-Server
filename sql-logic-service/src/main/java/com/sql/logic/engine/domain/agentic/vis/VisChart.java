package com.sql.logic.engine.domain.agentic.vis;

import java.util.*;

/**
 * Vis protocol for individual chart rendering.
 * Builds the structured JSON that the frontend AutoChart component consumes.
 */
public final class VisChart extends VisProtocol {

    public VisChart() {
        super("vis-db-chart");
    }

    /**
     * Build chart parameters from execution results.
     *
     * @param sql         the executed SQL
     * @param displayType chart type code (e.g. "response_bar_chart")
     * @param title       chart title
     * @param describe    thought/description from LLM
     * @param data        the result data rows
     * @param columns     column metadata (name, type)
     */
    public Map<String, Object> buildParams(
            String sql, String displayType, String title,
            String describe, List<Map<String, Object>> data,
            List<Map<String, String>> columns) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("sql", sql != null ? sql : "");
        params.put("type", displayType != null ? displayType : "response_table");
        params.put("title", title != null ? title : "");
        params.put("describe", describe != null ? describe : "");
        params.put("data", data != null ? data : List.of());
        if (columns != null && !columns.isEmpty()) {
            params.put("columns", columns);
        }
        return params;
    }

    /**
     * Convenience method that builds params and wraps for frontend.
     */
    public String render(String sql, String displayType, String title,
                         String describe, List<Map<String, Object>> data,
                         List<Map<String, String>> columns) {
        return display(buildParams(sql, displayType, title, describe, data, columns));
    }
}
