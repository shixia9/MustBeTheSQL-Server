package com.sql.logic.engine.common.dto;

import lombok.Data;

import java.util.List;

@Data
public class SqlGenerateRequest {
    private Long userId;
    private String userInput;
    private Long connectionId;
    private List<String> tableNames;
    private String schemaContext;
    @Deprecated
    private String strategyName;
    private Long llmConfigId;
    private Long parentHistoryId;
    /** Phase 4 HITL: when true, the LLM review gate is skipped and the plan auto-proceeds. */
    private Boolean autoConfirm;
}