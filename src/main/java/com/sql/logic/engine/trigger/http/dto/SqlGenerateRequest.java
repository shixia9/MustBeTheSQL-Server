package com.sql.logic.engine.trigger.http.dto;

import lombok.Data;

@Data
public class SqlGenerateRequest {
    private Long userId;
    private String userInput;
    private String schemaContext;
    private String strategyName = "openAiStrategy";
}
