package com.sql.logic.engine.trigger.http.dto;

import lombok.Data;

@Data
public class DdlExecuteRequest {
    private Long userId;
    private Long connectionId;
    private String sql;
    private Boolean autoCommit;
}