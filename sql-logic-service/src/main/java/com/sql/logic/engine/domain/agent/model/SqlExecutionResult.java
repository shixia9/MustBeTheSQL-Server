package com.sql.logic.engine.domain.agent.model;

import java.util.List;
import java.util.Map;

/**
 * Structured result of executing a single SQL statement on a user database,
 * produced by {@code SqlExecutionService} for the Agent's SQL_EXECUTION node.
 * <p>
 * Separated from {@code SqlExecuteResponse} (the HTTP console DTO) to keep the
 * Agent flow free of the console's validation/history side-effects and to carry
 * an explicit {@code errorMsg} field used by the self-correction loop.
 */
public class SqlExecutionResult {

    private List<String> columns;
    private List<Map<String, Object>> rows;
    private int rowCount;
    private String errorMsg;

    public SqlExecutionResult() {}

    public SqlExecutionResult(List<String> columns, List<Map<String, Object>> rows, int rowCount) {
        this.columns = columns;
        this.rows = rows;
        this.rowCount = rowCount;
    }

    public static SqlExecutionResult error(String errorMsg) {
        SqlExecutionResult r = new SqlExecutionResult();
        r.errorMsg = errorMsg;
        return r;
    }

    public boolean hasError() {
        return errorMsg != null && !errorMsg.isBlank();
    }

    public List<String> getColumns() { return columns; }
    public void setColumns(List<String> columns) { this.columns = columns; }

    public List<Map<String, Object>> getRows() { return rows; }
    public void setRows(List<Map<String, Object>> rows) { this.rows = rows; }

    public int getRowCount() { return rowCount; }
    public void setRowCount(int rowCount) { this.rowCount = rowCount; }

    public String getErrorMsg() { return errorMsg; }
    public void setErrorMsg(String errorMsg) { this.errorMsg = errorMsg; }
}