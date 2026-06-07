package com.sql.logic.engine.application.service.validator;

import com.sql.logic.engine.common.exception.BizException;
import com.sql.logic.engine.application.model.SqlConfirmInfo;
import com.sql.logic.engine.application.model.SqlExecuteContext;
import com.sql.logic.engine.application.model.SqlStatementCategory;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.alter.Alter;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.drop.Drop;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectBody;
import net.sf.jsqlparser.statement.show.ShowIndexStatement;
import net.sf.jsqlparser.statement.show.ShowTablesStatement;
import net.sf.jsqlparser.statement.truncate.Truncate;
import net.sf.jsqlparser.statement.update.Update;
import org.springframework.stereotype.Component;

@Component
public class SqlSafetyValidator implements SqlExecuteValidator {

    @Override
    public void validate(SqlExecuteContext context) {
        String sql = normalizeSql(context.getSql());
        if (sql.isEmpty()) {
            throw new IllegalArgumentException("SQL query cannot be empty");
        }

        Statements statements;
        try {
            statements = CCJSqlParserUtil.parseStatements(sql);
        } catch (JSQLParserException e) {
            throw new IllegalArgumentException("Invalid SQL syntax: " + e.getMessage());
        }

        if (statements.getStatements() == null || statements.getStatements().isEmpty()) {
            throw new IllegalArgumentException("SQL query cannot be empty");
        }
        if (statements.getStatements().size() != 1) {
            throw new IllegalArgumentException("Multiple SQL statements are not allowed");
        }

        Statement stmt = statements.getStatements().get(0);
        context.setStatementType(stmt.getClass().getSimpleName());

        if (isSafeRead(stmt)) {
            context.setCategory(SqlStatementCategory.SAFE_READ);
            context.setFinalSql(applyReadSafety(sql, stmt));
            return;
        }

        if (isDangerous(stmt)) {
            context.setCategory(SqlStatementCategory.NEEDS_CONFIRMATION);
            context.setFinalSql(sql);
            if (!context.isConfirmed()) {
                SqlConfirmInfo info = new SqlConfirmInfo();
                info.setStatementType(context.getStatementType());
                info.setSql(sql);
                throw new BizException(409, "Dangerous SQL detected. Confirmation is required to execute.", info);
            }
            return;
        }

        throw new IllegalArgumentException("Unsupported SQL statement type: " + context.getStatementType());
    }

    private static boolean isSafeRead(Statement stmt) {
        return (stmt instanceof Select)
                || (stmt instanceof ShowTablesStatement)
                || (stmt instanceof ShowIndexStatement);
    }

    private static boolean isDangerous(Statement stmt) {
        return (stmt instanceof Insert)
                || (stmt instanceof Update)
                || (stmt instanceof Delete)
                || (stmt instanceof Drop)
                || (stmt instanceof CreateTable)
                || (stmt instanceof Alter)
                || (stmt instanceof Truncate);
    }

    private static String applyReadSafety(String sql, Statement stmt) {
        String trimmed = normalizeSql(sql);
        if (stmt instanceof Select) {
            // Use AST to check for LIMIT clause instead of string matching
            // This avoids false positives from comments or subquery limits
            Select select = (Select) stmt;
            SelectBody body = select.getSelectBody();
            if (body instanceof PlainSelect) {
                PlainSelect plain = (PlainSelect) body;
                if (plain.getLimit() == null) {
                    return trimmed + " LIMIT 100";
                }
            }
        }
        return trimmed;
    }

    private static String normalizeSql(String sql) {
        if (sql == null) {
            return "";
        }
        String s = sql.trim();
        while (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1).trim();
        }
        return s;
    }
}
