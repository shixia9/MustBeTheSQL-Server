package com.sql.logic.engine.domain.agentic.workflow;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * Runtime variable definition for workflow parameterization.
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WorkflowVariable {
    private String name;
    private String type = "string";
    private String defaultValue;
}
