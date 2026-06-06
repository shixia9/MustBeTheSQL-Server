package com.sql.logic.engine.application.service.validator;

import com.sql.logic.engine.application.model.SqlExecuteContext;
import com.sql.logic.engine.application.service.DatabaseAppService;
import org.springframework.stereotype.Component;

@Component
public class ConnectionAccessValidator implements SqlExecuteValidator {

    private final DatabaseAppService databaseAppService;

    public ConnectionAccessValidator(DatabaseAppService databaseAppService) {
        this.databaseAppService = databaseAppService;
    }

    @Override
    public void validate(SqlExecuteContext context) {
        if (context.getConnectionId() == null) {
            throw new IllegalArgumentException("Connection ID is required");
        }
        databaseAppService.assertUserCanAccessConnection(context.getUserId(), context.getConnectionId());
    }
}
