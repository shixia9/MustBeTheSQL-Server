package com.sql.logic.engine.application.service;

import com.sql.logic.engine.application.model.SqlExecuteContext;
import com.sql.logic.engine.application.model.SqlStatementCategory;
import com.sql.logic.engine.application.service.validator.SqlExecuteValidationChain;
import com.sql.logic.engine.trigger.http.dto.SqlExecuteRequest;
import com.sql.logic.engine.trigger.http.dto.SqlExecuteResponse;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SQLExecuteAppService {

    private final SqlExecuteValidationChain validationChain;
    private final DatabaseAppService databaseAppService;
    private final QueryHistoryAppService queryHistoryAppService;

    public SQLExecuteAppService(SqlExecuteValidationChain validationChain, DatabaseAppService databaseAppService, QueryHistoryAppService queryHistoryAppService) {
        this.validationChain = validationChain;
        this.databaseAppService = databaseAppService;
        this.queryHistoryAppService = queryHistoryAppService;
    }

    public SqlExecuteResponse execute(SqlExecuteRequest request) {
        SqlExecuteContext context = new SqlExecuteContext();
        context.setUserId(request.getUserId());
        context.setConnectionId(request.getConnectionId());
        context.setSql(request.getSql());
        context.setConfirmed(Boolean.TRUE.equals(request.getConfirmed()));

        validationChain.validate(context);

        SqlExecuteResponse response = new SqlExecuteResponse();
        response.setExecutedSql(context.getFinalSql());
        response.setStatementType(context.getStatementType());

        try (Connection conn = databaseAppService.getConnectionForUser(context.getUserId(), context.getConnectionId());
             Statement stmt = conn.createStatement()) {

            stmt.setQueryTimeout(10);
            if (context.getCategory() == SqlStatementCategory.SAFE_READ) {
                stmt.setMaxRows(100);
            }

            long startTime = System.currentTimeMillis();
            boolean hasResultSet = stmt.execute(context.getFinalSql());
            long latency = System.currentTimeMillis() - startTime;
            
            int rowCountForHistory = 0;

            if (hasResultSet) {
                response.setResultType("QUERY");
                try (ResultSet rs = stmt.getResultSet()) {
                    QueryResult qr = readResultSet(rs);
                    response.setColumns(qr.columns);
                    response.setRows(qr.rows);
                    response.setRowCount(qr.rows.size());
                    rowCountForHistory = qr.rows.size();
                }
            } else {
                response.setResultType("UPDATE");
                int affected = stmt.getUpdateCount();
                response.setAffectedRows(affected >= 0 ? affected : null);
                rowCountForHistory = affected >= 0 ? affected : 0;
            }
            
            try {
                if (request.getParentHistoryId() != null) {
                    queryHistoryAppService.recordReRunExecution(request.getUserId(), request.getParentHistoryId(), latency, rowCountForHistory);
                } else {
                    queryHistoryAppService.recordExecution(request.getUserId(), request.getSql(), latency, rowCountForHistory);
                }
            } catch (Exception ignored) {}
            
        } catch (SQLException e) {
            throw new IllegalArgumentException("SQL Execution Error: " + e.getMessage());
        }

        return response;
    }

    private static QueryResult readResultSet(ResultSet rs) throws SQLException {
        ResultSetMetaData metaData = rs.getMetaData();
        int columnCount = metaData.getColumnCount();

        List<String> columns = new ArrayList<>();
        for (int i = 1; i <= columnCount; i++) {
            columns.add(metaData.getColumnLabel(i));
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        while (rs.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= columnCount; i++) {
                row.put(columns.get(i - 1), rs.getObject(i));
            }
            rows.add(row);
        }

        return new QueryResult(columns, rows);
    }

    private record QueryResult(List<String> columns, List<Map<String, Object>> rows) {
    }
}
