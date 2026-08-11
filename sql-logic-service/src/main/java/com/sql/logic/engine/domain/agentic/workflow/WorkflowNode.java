package com.sql.logic.engine.domain.agentic.workflow;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A node in the workflow graph.
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WorkflowNode {
    private String id;
    /** Node type: agent, start, end, condition, parallel, resource */
    private String type;
    private Position position;
    private NodeData data;

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Position {
        private double x;
        private double y;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class NodeData {
        private String title;
        /** Agent name for agent-type nodes */
        private String agentName;
        /** Runtime configuration values */
        private Map<String, Object> inputsValues = new LinkedHashMap<>();
    }
}
