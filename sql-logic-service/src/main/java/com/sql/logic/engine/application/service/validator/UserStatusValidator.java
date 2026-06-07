package com.sql.logic.engine.application.service.validator;

import com.sql.logic.engine.application.model.SqlExecuteContext;
import org.springframework.stereotype.Component;

@Component
public class UserStatusValidator implements SqlExecuteValidator {

    @Override
    public void validate(SqlExecuteContext context) {
        if (context.getUser() == null) {
            throw new IllegalStateException("User not loaded");
        }
        if (context.getUser().getStatus() == null || context.getUser().getStatus() != 1) {
            throw new IllegalStateException("User account is not active. Status: " + context.getUser().getStatus());
        }
    }
}
