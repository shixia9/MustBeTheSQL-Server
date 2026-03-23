package com.sql.logic.engine.application.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

@Service
public class SQLExecuteAppService {

    private final SQLValidationService sqlValidationService;
    private final JdbcTemplate jdbcTemplate;

    public SQLExecuteAppService(SQLValidationService sqlValidationService, DataSource dataSource) {
        this.sqlValidationService = sqlValidationService;
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        // Set query timeout to 10 seconds to avoid slow queries blocking the system
        this.jdbcTemplate.setQueryTimeout(10);
    }

    public List<Map<String, Object>> executeQuery(String sql) {
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
        
        // 3. Execute
        return jdbcTemplate.queryForList(finalSql);
    }
}
