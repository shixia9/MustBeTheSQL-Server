package com.sql.logic.engine.domain.agentic.vis;

import java.util.List;

/**
 * Structured dashboard data containing multiple chart items.
 * Serialized as JSON and transmitted to the frontend for grid rendering.
 */
public record DashboardData(
        List<ChartItem> charts,
        int chartCount,
        String title,
        String displayStrategy,
        String style
) {
    public DashboardData {
        chartCount = charts != null ? charts.size() : 0;
        displayStrategy = displayStrategy != null ? displayStrategy : "default";
        style = style != null ? style : "default";
    }

    public static DashboardData of(List<ChartItem> charts, String title) {
        return new DashboardData(charts, charts.size(), title, "default", "default");
    }
}
