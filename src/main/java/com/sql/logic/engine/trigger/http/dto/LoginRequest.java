package com.sql.logic.engine.trigger.http.dto;

import lombok.Data;

@Data
public class LoginRequest {
    private String email;
    private String password;
}
