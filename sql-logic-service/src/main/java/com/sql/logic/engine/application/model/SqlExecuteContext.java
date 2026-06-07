package com.sql.logic.engine.application.model;

import com.sql.logic.engine.infrastructure.po.UserInfo;
import lombok.Data;

@Data
public class SqlExecuteContext {
    private Long userId;
    private Long connectionId;
    private String sql;
    private boolean confirmed;

    private UserInfo user;
    private SqlStatementCategory category;
    private String statementType;
    private String finalSql;
}
