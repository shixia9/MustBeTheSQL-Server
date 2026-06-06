package com.sql.logic.engine.common.dto;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;

@Data
@Getter
@Setter
public class LoginRequest {
    private String email;
    private String password;
    private Boolean rememberMe;
}