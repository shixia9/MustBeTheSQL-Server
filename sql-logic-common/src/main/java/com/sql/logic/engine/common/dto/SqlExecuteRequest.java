package com.sql.logic.engine.common.dto;

import lombok.Data;

@Data
public class SqlExecuteRequest {
    private Long userId;
    private String sql;
    private Long connectionId;
    private Boolean confirmed;
    private Long parentHistoryId;
}