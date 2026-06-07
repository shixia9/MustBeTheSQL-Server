package com.sql.logic.engine.application.service.validator;

import com.sql.logic.engine.application.model.SqlExecuteContext;

public interface SqlExecuteValidator {
    void validate(SqlExecuteContext context);
}
