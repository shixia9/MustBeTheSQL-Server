package com.sql.logic.engine.common.dto;

import lombok.Data;

@Data
public class ScheduledTaskCreateRequest {
    private String name;
    private String cronExpr;
    private String taskType;
    private String payload;
}
