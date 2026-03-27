package com.sql.logic.engine.application.service;

import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class SQLExecuteAppService {

    private final SQLValidationService sqlValidationService;
    private final DatabaseAppService databaseAppService;

    public SQLExecuteAppService(SQLValidationService sqlValidationService, DatabaseAppService databaseAppService) {
        this.sqlValidationService = sqlValidationService;
        this.databaseAppService = databaseAppService;
    }

    public List<Map<String, Object>> executeQuery(String sql, Long connectionId) {
        // Check if SQL is empty
        if (sql == null || sql.trim().isEmpty()) {
            throw new IllegalArgumentException("SQL query cannot be empty");
        }

        // 1. Validate SQL to prevent DML/DDL
        sqlValidationService.validateSQL(sql);
        
        // 2. Add LIMIT if not present (simplified check)
        String finalSql = sql;
        if (!sql.toLowerCase().contains("limit")) {
            finalSql = sql + " LIMIT 100";
        }
        
        // 3. Execute dynamically
        List<Map<String, Object>> results = new ArrayList<>();
        try (Connection conn = databaseAppService.getConnection(connectionId);
             Statement stmt = conn.createStatement()) {
             
            stmt.setQueryTimeout(10); // 10 seconds timeout
            
            try (ResultSet rs = stmt.executeQuery(finalSql)) {
                ResultSetMetaData metaData = rs.getMetaData();
                int columnCount = metaData.getColumnCount();
                
                while (rs.next()) {
                    Map<String, Object> row = new HashMap<>();
                    for (int i = 1; i <= columnCount; i++) {
                        row.put(metaData.getColumnName(i), rs.getObject(i));
                    }
                    results.add(row);
                }
            }
        } catch (SQLException e) {
            throw new IllegalArgumentException("SQL Execution Error: " + e.getMessage());
        }
        
        return results;
    }
}
