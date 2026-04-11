package com.sql.logic.engine.application.service.validator;

import com.sql.logic.engine.application.model.SqlExecuteContext;
import org.springframework.stereotype.Component;

@Component
public class SqlExecuteValidationChain {

    private final LoginValidator loginValidator;
    private final UserStatusValidator userStatusValidator;
    private final ConnectionAccessValidator connectionAccessValidator;
    private final SqlSafetyValidator sqlSafetyValidator;
    private final TokenQuotaValidator tokenQuotaValidator;

    public SqlExecuteValidationChain(LoginValidator loginValidator,
                                    UserStatusValidator userStatusValidator,
                                    ConnectionAccessValidator connectionAccessValidator,
                                    SqlSafetyValidator sqlSafetyValidator,
                                    TokenQuotaValidator tokenQuotaValidator) {
        this.loginValidator = loginValidator;
        this.userStatusValidator = userStatusValidator;
        this.connectionAccessValidator = connectionAccessValidator;
        this.sqlSafetyValidator = sqlSafetyValidator;
        this.tokenQuotaValidator = tokenQuotaValidator;
    }

    public void validate(SqlExecuteContext context) {
        loginValidator.validate(context);
        userStatusValidator.validate(context);
        connectionAccessValidator.validate(context);
        sqlSafetyValidator.validate(context);
        tokenQuotaValidator.validate(context);
    }
}
