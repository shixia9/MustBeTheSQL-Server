package com.sql.logic.engine.trigger.http.dto;

import lombok.Data;

@Data
public class SqlExecuteRequest {
    private String sql;
    private Long connectionId;
}
