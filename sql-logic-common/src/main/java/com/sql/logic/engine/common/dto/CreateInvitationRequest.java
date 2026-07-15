package com.sql.logic.engine.common.dto;

public class CreateInvitationRequest {
    private String role;
    private Integer expiresInHours;

    public CreateInvitationRequest() {
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public Integer getExpiresInHours() {
        return expiresInHours;
    }

    public void setExpiresInHours(Integer expiresInHours) {
        this.expiresInHours = expiresInHours;
    }
}
