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
    private Boolean autoConfirm;
    private Long workspaceId;
    /** Phase B (B5): multi-turn conversation id. Null/blank on the first turn — the
     *  backend resolves/creates a conversation and the frontend echoes the returned id
     *  on subsequent follow-ups so prior turns can be injected as context. */
    private Long conversationId;
}