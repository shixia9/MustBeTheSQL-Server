package com.sql.logic.engine.common.dto;

import lombok.Data;

@Data
public class ScheduledTaskCreateRequest {
    private String name;
    private String cronExpr;
    private String taskType;
    private String payload;
    private String description;
    private String timeZone;
    private Integer timeoutSeconds;
    private Integer maxRetries;
    private Integer payloadVersion;
}
