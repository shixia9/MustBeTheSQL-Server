package com.sql.logic.engine.common.dto;

import lombok.Data;

@Data
public class ScheduledTaskUpdateRequest {
    private Long id;
    private String name;
    private String cronExpr;
    private String taskType;
    private String payload;
}
