package com.sql.logic.engine.domain.agentic.vis;

import java.util.List;
import java.util.Map;

/**
 * A single chart item within a dashboard or standalone chart response.
 */
public record ChartItem(
        String title,
        String displayType,
        String sql,
        String thought,
        List<Map<String, Object>> data,
        String error
) {
    public static ChartItem of(String title, String displayType, String sql, String thought) {
        return new ChartItem(title, displayType, sql, thought, null, null);
    }

    public ChartItem withData(List<Map<String, Object>> data) {
        return new ChartItem(title, displayType, sql, thought, data, error);
    }

    public ChartItem withError(String error) {
        return new ChartItem(title, displayType, sql, thought, data, error);
    }
}
