package com.sql.logic.engine.domain.agentic.workflow;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Top-level JSON workflow definition.
 * <pre>
 * { "version": "1.0", "name": "...", "variables": [...], "nodes": [...], "edges": [...] }
 * </pre>
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WorkflowDefinition {
    private String version = "1.0";
    private String name;
    private String description;
    private List<WorkflowVariable> variables = new ArrayList<>();
    private List<WorkflowNode> nodes = new ArrayList<>();
    private List<WorkflowEdge> edges = new ArrayList<>();
}
