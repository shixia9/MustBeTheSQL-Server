package com.sql.logic.engine.trigger.http.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class SqlExecuteResponse {
    private String executedSql;
    private String statementType;
    private String resultType;
    private Integer rowCount;
    private Integer affectedRows;
    private List<String> columns;
    private List<Map<String, Object>> rows;
}
