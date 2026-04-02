package com.sql.logic.engine.application.service.validator;

import com.sql.logic.engine.application.model.SqlExecuteContext;
import com.sql.logic.engine.application.service.UserAppService;
import com.sql.logic.engine.infrastructure.po.UserInfo;
import org.springframework.stereotype.Component;

@Component
public class LoginValidator implements SqlExecuteValidator {

    private final UserAppService userAppService;

    public LoginValidator(UserAppService userAppService) {
        this.userAppService = userAppService;
    }

    @Override
    public void validate(SqlExecuteContext context) {
        if (context.getUserId() == null) {
            throw new IllegalArgumentException("UserId is required");
        }
        UserInfo user = userAppService.getUserById(context.getUserId());
        context.setUser(user);
    }
}
