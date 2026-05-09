package com.sql.logic.engine.application.service;

import com.sql.logic.engine.infrastructure.dao.DdlAuditLogDao;
import com.sql.logic.engine.infrastructure.po.DdlAuditLog;
import com.sql.logic.engine.trigger.http.dto.DdlExecuteRequest;
import com.sql.logic.engine.trigger.http.dto.DdlExecuteResponse;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Service
public class DdlExecutionAppService {

    private final DatabaseAppService databaseAppService;
    private final DdlAuditLogDao ddlAuditLogDao;

    public DdlExecutionAppService(DatabaseAppService databaseAppService, DdlAuditLogDao ddlAuditLogDao) {
        this.databaseAppService = databaseAppService;
        this.ddlAuditLogDao = ddlAuditLogDao;
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

    public DdlExecuteResponse execute(DdlExecuteRequest request) {
        DdlExecuteResponse response = new DdlExecuteResponse();
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
                        stmt.setQueryTimeout(300); // 300 seconds timeout for DDL
                        boolean hasResultSet = stmt.execute(sqlStmt);
                        if (!hasResultSet) {
                            int affected = stmt.getUpdateCount();
                            if (affected > 0) {
                                totalAffectedRows += affected;
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
        
        // Log Audit
        try {
            DdlAuditLog log = new DdlAuditLog();
            log.setUserId(request.getUserId());
            log.setConnectionId(request.getConnectionId());
            log.setSqlScript(request.getSql());
            log.setExecuteLatency(latency);
            log.setStatus(success ? "SUCCESS" : "FAILED");
            log.setErrorMessage(errorMessage);
            log.setCreateTime(new Date());
            // clientIp could be populated if passed through context, but leaving null for now
            ddlAuditLogDao.insert(log);
        } catch (Exception e) {
            // Ignore audit log failure
        }

        return response;
    }
}