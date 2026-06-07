package com.sql.logic.engine.common.dto;

import lombok.Data;

@Data
public class UpdatePasswordRequest {
    private Long userId;
    private String oldPassword;
    private String newPassword;
}