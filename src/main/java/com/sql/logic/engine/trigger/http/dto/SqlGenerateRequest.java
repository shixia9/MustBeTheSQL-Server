package com.sql.logic.engine.trigger.http.dto;

import lombok.Data;

import java.util.List;

@Data
public class SqlGenerateRequest {
    private Long userId;
    private String userInput;
    private Long connectionId;
    private List<String> tableNames;
    private String schemaContext;
    private String strategyName; // 例如: "openAiStrategy"
    private Long parentHistoryId; // 用于记录重新执行的历史来源
}
