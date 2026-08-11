package com.sql.logic.engine.common.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

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
    private Boolean autoConfirm;
    private Long workspaceId;

    private Long conversationId;

    /** Request HTML report format from DashboardAction. */
    private Boolean htmlReport;

    /** Direct tool invocation request. */
    private Map<String, Object> toolInvocation;
}