package com.sql.logic.engine.application.service;

import com.sql.logic.engine.application.service.validator.ConsoleSqlSafetyValidator;
import com.sql.logic.engine.infrastructure.annotation.RecordSqlAudit;
import com.sql.logic.engine.trigger.http.dto.SqlConsoleExecuteRequest;
import com.sql.logic.engine.trigger.http.dto.SqlConsoleExecuteResponse;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(SqlConsoleAppService.class);

    private final DatabaseAppService databaseAppService;
    private final ConsoleSqlSafetyValidator safetyValidator;
    private final DatabaseMetaDataService databaseMetaDataService;

    public SqlConsoleAppService(DatabaseAppService databaseAppService, ConsoleSqlSafetyValidator safetyValidator, DatabaseMetaDataService databaseMetaDataService) {
        this.databaseAppService = databaseAppService;
        this.safetyValidator = safetyValidator;
        this.databaseMetaDataService = databaseMetaDataService;
    }

    /**
     * Split SQL into individual statements using JSQLParser.
     * This properly handles semicolons inside string literals, unlike naive split(";").
     * Falls back to simple splitting if JSQLParser cannot parse the input.
     */
    private List<String> splitSql(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            return new ArrayList<>();
        }

        // Try JSQLParser first for smart splitting (respects string literals)
        try {
            Statements statements = CCJSqlParserUtil.parseStatements(sql);
            List<String> result = new ArrayList<>();
            for (net.sf.jsqlparser.statement.Statement stmt : statements.getStatements()) {
                String trimmed = stmt.toString().trim();
                if (!trimmed.isEmpty()) {
                    result.add(trimmed);
                }
            }
            if (!result.isEmpty()) {
                return result;
            }
        } catch (JSQLParserException e) {
            log.debug("JSQLParser could not parse multi-statement SQL, falling back to simple split", e);
        }

        // Fallback: simple semicolon split (original behavior)
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

        // Validate each statement for safety before execution
        for (String sqlStmt : statements) {
            try {
                safetyValidator.validate(sqlStmt);
            } catch (Exception e) {
                response.setSuccess(false);
                response.setErrorMessage(e.getMessage());
                response.setLatency(System.currentTimeMillis() - startTime);
                return response;
            }
        }

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
                        stmt.setQueryTimeout(60); // 60 seconds timeout (reduced from 300)
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

                    // If this was a DDL statement, invalidate the DDL cache for this connection
                    // so subsequent AI queries get fresh schema information
                    if (isDDLStatement(sqlStmt) && request.getConnectionId() != null) {
                        databaseMetaDataService.clearCache(request.getConnectionId());
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

    /**
     * Detect if a SQL statement is a DDL (Data Definition Language) statement
     * that would change the database schema and thus invalidate cached metadata.
     */
    private boolean isDDLStatement(String sql) {
        if (sql == null) return false;
        String upper = sql.trim().toUpperCase();
        return upper.startsWith("CREATE ") || upper.startsWith("ALTER ") ||
               upper.startsWith("DROP ") || upper.startsWith("TRUNCATE ") ||
               upper.startsWith("RENAME ");
    }
}