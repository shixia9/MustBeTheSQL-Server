package com.sql.logic.engine.application.service.validator;

import com.sql.logic.engine.application.model.SqlExecuteContext;
import com.sql.logic.engine.application.service.UserAppService;
import org.springframework.stereotype.Component;

@Component
public class TokenQuotaValidator implements SqlExecuteValidator {

    private final UserAppService userAppService;

    public TokenQuotaValidator(UserAppService userAppService) {
        this.userAppService = userAppService;
    }

    @Override
    public void validate(SqlExecuteContext context) {
        userAppService.deductToken(context.getUser());
    }
}
