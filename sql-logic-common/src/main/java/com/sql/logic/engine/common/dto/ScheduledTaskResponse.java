package com.sql.logic.engine.common.dto;

import lombok.Data;

@Data
public class ScheduledTaskResponse {
    private Long id;
    private String name;
    private String cronExpr;
    private String taskType;
    private String payload;
    private Integer status;
    private String lastRunTime;
    private String nextRunTime;
    private String createTime;
    private String updateTime;
    private String description;
    private String timeZone;
    private Integer timeoutSeconds;
    private Integer maxRetries;
    private String lastRunStatus;
    private Long lastRunId;
    private Integer payloadVersion;
}
