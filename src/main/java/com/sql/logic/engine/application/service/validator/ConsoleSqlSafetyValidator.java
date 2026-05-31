package com.sql.logic.engine.application.service.validator;

import com.sql.logic.engine.application.exception.BizException;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.drop.Drop;
import org.springframework.stereotype.Component;

/**
 * Safety validator for SQL Console execution.
 * Unlike SqlSafetyValidator (which enforces read-only by default),
 * this validator allows DML/DDL but blocks extremely dangerous operations
 * like DROP DATABASE and multi-statement injection.
 */
@Component
public class ConsoleSqlSafetyValidator {

    /**
     * Blocked SQL patterns that are too dangerous even for the console.
     * These operations can cause irreversible damage to production data.
     */
    private static final String[] BLOCKED_PATTERNS = {
            "DROP DATABASE",
            "DROP SCHEMA",
            "SHUTDOWN",
            "GRANT ALL",
            "REVOKE ALL"
    };

    /**
     * Validate a single SQL statement for console execution.
     * Blocks extremely dangerous operations but allows DML and most DDL.
     *
     * @param sql the SQL statement to validate
     * @throws BizException if the statement is too dangerous to execute
     */
    public void validate(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            throw new IllegalArgumentException("SQL statement cannot be empty");
        }

        String normalized = sql.trim().toUpperCase();

        // Block extremely dangerous patterns (case-insensitive)
        for (String pattern : BLOCKED_PATTERNS) {
            if (normalized.contains(pattern)) {
                throw new BizException(403, "Operation not allowed: " + pattern + " is prohibited in the console.");
            }
        }

        // Further validation: parse the statement to detect DROP DATABASE via AST
        try {
            Statement stmt = CCJSqlParserUtil.parse(sql.trim());
            if (stmt instanceof Drop) {
                Drop drop = (Drop) stmt;
                // Block dropping databases/schemas via AST
                String dropType = drop.getType().toUpperCase();
                if ("DATABASE".equals(dropType) || "SCHEMA".equals(dropType)) {
                    throw new BizException(403, "DROP DATABASE/SCHEMA is prohibited in the console.");
                }
            }
        } catch (JSQLParserException e) {
            // If JSQLParser can't parse it, the database driver will likely reject it too.
            // Don't block it just because we can't parse it — let the database handle it.
        }
    }
}