package com.sql.logic.engine.application.service;

import com.sql.logic.engine.infrastructure.annotation.RecordSqlAudit;
import com.sql.logic.engine.trigger.http.dto.SqlConsoleExecuteRequest;
import com.sql.logic.engine.trigger.http.dto.SqlConsoleExecuteResponse;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SqlConsoleAppService {

    private final DatabaseAppService databaseAppService;

    public SqlConsoleAppService(DatabaseAppService databaseAppService) {
        this.databaseAppService = databaseAppService;
    }
    
    private List<String> splitSql(String sql) {
        if (sql == null) return new ArrayList<>();
        List<String> result = new ArrayList<>();
        String[] parts = sql.split(";");
        for (String part : parts) {
            if (!part.trim().isEmpty()) {
                result.add(part.trim());
            }
        }
        return result;
    }

    @RecordSqlAudit
    public SqlConsoleExecuteResponse execute(SqlConsoleExecuteRequest request) {
        SqlConsoleExecuteResponse response = new SqlConsoleExecuteResponse();
        long startTime = System.currentTimeMillis();
        
        List<String> statements = splitSql(request.getSql());
        
        int totalAffectedRows = 0;
        boolean success = true;
        String errorMessage = null;

        try (Connection conn = databaseAppService.getConnectionForUser(request.getUserId(), request.getConnectionId())) {
            boolean originalAutoCommit = conn.getAutoCommit();
            boolean useAutoCommit = request.getAutoCommit() != null ? request.getAutoCommit() : true;
            
            try {
                conn.setAutoCommit(useAutoCommit);
                
                for (String sqlStmt : statements) {
                    if (sqlStmt.trim().isEmpty()) continue;
                    
                    try (Statement stmt = conn.createStatement()) {
                        stmt.setQueryTimeout(300); // 300 seconds timeout
                        boolean hasResultSet = stmt.execute(sqlStmt);
                        if (!hasResultSet) {
                            int affected = stmt.getUpdateCount();
                            if (affected > 0) {
                                totalAffectedRows += affected;
                            }
                        } else {
                            try (ResultSet rs = stmt.getResultSet()) {
                                ResultSetMetaData metaData = rs.getMetaData();
                                int columnCount = metaData.getColumnCount();
                                List<String> cols = new ArrayList<>();
                                for (int i = 1; i <= columnCount; i++) {
                                    cols.add(metaData.getColumnLabel(i));
                                }
                                response.setColumns(cols);

                                List<Map<String, Object>> rows = new ArrayList<>();
                                int rowLimit = 500; // Hard limit for safety
                                while (rs.next() && rows.size() < rowLimit) {
                                    Map<String, Object> row = new LinkedHashMap<>();
                                    for (int i = 1; i <= columnCount; i++) {
                                        row.put(cols.get(i - 1), rs.getObject(i));
                                    }
                                    rows.add(row);
                                }
                                response.setRows(rows);
                            }
                        }
                    }
                }
                
                if (!useAutoCommit) {
                    conn.commit();
                }
                
            } catch (SQLException e) {
                success = false;
                errorMessage = e.getMessage();
                
                // Basic driver-level mapping for line number
                if (errorMessage != null) {
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("at line (\\d+)").matcher(errorMessage);
                    if (m.find()) {
                        response.setErrorLine(Integer.parseInt(m.group(1)));
                    }
                }
                
                if (!useAutoCommit) {
                    try { conn.rollback(); } catch (SQLException ignored) {}
                }
            } finally {
                try { conn.setAutoCommit(originalAutoCommit); } catch (SQLException ignored) {}
            }
        } catch (Exception e) {
            success = false;
            errorMessage = "Connection error: " + e.getMessage();
        }

        long latency = System.currentTimeMillis() - startTime;
        response.setSuccess(success);
        response.setLatency(latency);
        response.setAffectedRows(totalAffectedRows);
        response.setErrorMessage(errorMessage);

        return response;
    }
}