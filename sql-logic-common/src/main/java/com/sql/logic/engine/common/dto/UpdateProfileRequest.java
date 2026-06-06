package com.sql.logic.engine.common.dto;

import lombok.Data;

@Data
public class UpdateProfileRequest {
    private Long userId;
    private String username;
    private String email;
}