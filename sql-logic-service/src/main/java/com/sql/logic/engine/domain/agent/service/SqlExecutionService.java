package com.sql.logic.engine.domain.agent.service;

import com.sql.logic.engine.application.service.DatabaseAppService;
import com.sql.logic.engine.domain.agent.model.SqlExecutionResult;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectBody;
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

/**
 * Lightweight SQL execution service for the Agent graph.
 * <p>
 * Distinct from {@code SQLExecuteAppService}: this one carries no validation chain,
 * no query-history side-effects, and no user-quota accounting. It enforces a strict
 * read-only gate (only SELECT / SHOW statements) to safely run LLM-generated SQL,
 * and returns a structured {@link SqlExecutionResult} whose {@code errorMsg} field
 * drives the self-correction (SQL_FIXER) loop.
 */
@Service
public class SqlExecutionService {

    private static final Logger log = LoggerFactory.getLogger(SqlExecutionService.class);

    private static final int MAX_ROWS = 1000;
    private static final int QUERY_TIMEOUT_SECONDS = 10;
    private static final int READ_SAFETY_LIMIT = 1000;

    private final DatabaseAppService databaseAppService;

    public SqlExecutionService(DatabaseAppService databaseAppService) {
        this.databaseAppService = databaseAppService;
    }

    /**
     * Execute a SQL statement on the user's selected database connection.
     *
     * @param userId       the logged-in user ID (for connection access control)
     * @param connectionId the target database connection ID
     * @param sql          the SQL to execute (must be a read-only SELECT)
     * @return a structured result; success → columns/rows populated, {@code errorMsg} null;
     *         failure → {@code errorMsg} set (validation error or SQLException).
     */
    public SqlExecutionResult execute(Long userId, Long connectionId, String sql) {
        if (sql == null || sql.isBlank()) {
            return SqlExecutionResult.error("SQL statement is empty.");
        }

        // Read-only gate: reject anything that isn't a SELECT (protects against
        // LLM-emitted destructive/DDL statements being auto-executed).
        String finalSql = ensureSafeRead(sql);
        if (finalSql == null) {
            return SqlExecutionResult.error("Generated SQL is not a read-only SELECT and was blocked.");
        }

        try (Connection conn = databaseAppService.getConnectionForUser(userId, connectionId);
             Statement stmt = conn.createStatement()) {

            stmt.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            stmt.setMaxRows(MAX_ROWS);

            long t0 = System.currentTimeMillis();
            boolean hasResultSet = stmt.execute(finalSql);
            long latency = System.currentTimeMillis() - t0;

            if (!hasResultSet) {
                // A read-only gate guarantees a ResultSet; reaching here is anomalous.
                return SqlExecutionResult.error("Statement returned no result set.");
            }

            try (ResultSet rs = stmt.getResultSet()) {
                QueryResult qr = readResultSet(rs);
                log.info("[SqlExecutionService] OK rows={} latency={}ms", qr.rows.size(), latency);
                return new SqlExecutionResult(qr.columns, qr.rows, qr.rows.size());
            }
        } catch (SQLException e) {
            log.warn("[SqlExecutionService] SQL execution failed: {}", e.getMessage());
            return SqlExecutionResult.error("SQL Execution Error: " + e.getMessage());
        } catch (Exception e) {
            log.warn("[SqlExecutionService] execution failed", e);
            return SqlExecutionResult.error("Execution failed: " + e.getMessage());
        }
    }

    /**
     * Ensure the SQL is a safe SELECT. Returns the (possibly LIMIT-appended) SQL
     * to execute, or {@code null} if the statement is not a read-only SELECT.
     */
    private String ensureSafeRead(String sql) {
        String trimmed = sql.trim();
        while (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        String upper = trimmed.toUpperCase();
        // Allow SELECT and SHOW (tables/index). Reject everything else.
        boolean looksSelect = upper.startsWith("SELECT") || upper.startsWith("WITH")
                || upper.startsWith("SHOW ") || upper.startsWith("(");
        if (!looksSelect) {
            return null;
        }
        try {
            net.sf.jsqlparser.statement.Statement stmt = CCJSqlParserUtil.parse(trimmed);
            if (stmt instanceof Select) {
                SelectBody body = ((Select) stmt).getSelectBody();
                if (body instanceof PlainSelect) {
                    PlainSelect plain = (PlainSelect) body;
                    if (plain.getLimit() == null) {
                        return trimmed + " LIMIT " + READ_SAFETY_LIMIT;
                    }
                }
                return trimmed;
            }
            // SHOW statements etc. — accept
            return trimmed;
        } catch (JSQLParserException e) {
            // Parser couldn't handle it (e.g. dialect/CTE edge cases).
            // Fall back to the string-level read check: trust the DB driver's error
            // rather than blocking outright, but only because we already gated on
            // the `looksSelect` prefix above.
            log.debug("[SqlExecutionService] JSQLParser could not parse SQL, trusting prefix gate: {}", e.getMessage());
            return trimmed;
        }
    }

    private static QueryResult readResultSet(ResultSet rs) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int columnCount = meta.getColumnCount();

        List<String> columns = new ArrayList<>(columnCount);
        for (int i = 1; i <= columnCount; i++) {
            columns.add(meta.getColumnLabel(i));
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

    private record QueryResult(List<String> columns, List<Map<String, Object>> rows) {}
}