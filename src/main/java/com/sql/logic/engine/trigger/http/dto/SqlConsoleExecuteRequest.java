package com.sql.logic.engine.trigger.http.dto;

import lombok.Data;

@Data
public class SqlConsoleExecuteRequest {
    private Long connectionId;
    private String sql;
    private Boolean autoCommit;
    private Long userId;
}