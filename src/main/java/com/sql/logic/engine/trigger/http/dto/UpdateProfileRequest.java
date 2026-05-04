package com.sql.logic.engine.trigger.http.dto;

import lombok.Data;

@Data
public class UpdateProfileRequest {
    private Long userId;
    private String username;
    private String email;
}
