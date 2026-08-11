package com.sql.logic.engine.domain.agentic.workflow;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * An edge connecting two workflow nodes.
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WorkflowEdge {
    private String sourceNodeId;
    private String targetNodeId;
    /** Optional condition value for conditional edges */
    private String condition;
}
