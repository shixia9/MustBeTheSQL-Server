package com.sql.logic.engine.domain.agentic.vis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Vis protocol for multi-chart dashboard rendering.
 * Builds the structured JSON that the frontend DashboardGrid component consumes.
 */
public final class VisDashboard extends VisProtocol {

    private static final Logger log = LoggerFactory.getLogger(VisDashboard.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    public VisDashboard() {
        super("vis-dashboard");
    }

    /**
     * Build dashboard parameters from a list of chart items.
     */
    public Map<String, Object> buildParams(List<ChartItem> charts, String title) {
        List<Map<String, Object>> chartData = new ArrayList<>();
        for (ChartItem item : charts) {
            Map<String, Object> cd = new LinkedHashMap<>();
            cd.put("title", item.title());
            cd.put("type", item.displayType());
            cd.put("sql", item.sql());
            cd.put("describe", item.thought());
            if (item.data() != null) {
                cd.put("data", item.data());
            }
            if (item.error() != null) {
                cd.put("error", item.error());
            }
            chartData.add(cd);
        }

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("data", chartData);
        params.put("chart_count", charts.size());
        params.put("title", title != null ? title : "Analysis Dashboard");
        params.put("display_strategy", "default");
        params.put("style", "default");
        return params;
    }

    /**
     * Convenience: build params, wrap for frontend, and return the full display string.
     */
    public String render(List<ChartItem> charts, String title) {
        return display(buildParams(charts, title));
    }

    /**
     * Serialize DashboardData to JSON string (for direct REST API responses).
     */
    public String toJson(DashboardData data) {
        try {
            return mapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize dashboard data", e);
            return "{}";
        }
    }
}
