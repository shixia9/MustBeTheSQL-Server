package com.sql.logic.engine.trigger.http.dto;

import lombok.Data;

@Data
public class DdlExecuteResponse {
    private Boolean success;
    private Integer affectedRows;
    private Long latency;
    private Integer errorLine;
    private String errorMessage;
}