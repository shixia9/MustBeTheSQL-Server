package com.sql.logic.engine.application.service;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.show.ShowTablesStatement;
import net.sf.jsqlparser.statement.show.ShowIndexStatement;
import org.springframework.stereotype.Service;

@Service
public class SQLValidationService {

    public void validateSQL(String sql) {
        try {
            Statement stmt = CCJSqlParserUtil.parse(sql);
            
            // Only allow SELECT, SHOW
            if (!(stmt instanceof Select) && 
                !(stmt instanceof ShowTablesStatement) && !(stmt instanceof ShowIndexStatement)) {
                throw new IllegalArgumentException("Only SELECT, EXPLAIN, and SHOW queries are allowed for security reasons.");
            }
            
            // Note: For full safety, we'd also check sub-queries or multiple statements.
            
        } catch (JSQLParserException e) {
            throw new IllegalArgumentException("Invalid SQL syntax: " + e.getMessage());
        }
    }
}
