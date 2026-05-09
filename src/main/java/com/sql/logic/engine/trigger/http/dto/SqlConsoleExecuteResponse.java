package com.sql.logic.engine.trigger.http.dto;

import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class SqlConsoleExecuteResponse {
    private boolean success;
    private int affectedRows;
    private long latency;
    private Integer errorLine;
    private String errorMessage;
    private List<String> columns;
    private List<Map<String, Object>> rows;
}