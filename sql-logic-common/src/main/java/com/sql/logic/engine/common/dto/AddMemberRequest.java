package com.sql.logic.engine.common.dto;

public class AddMemberRequest {

    private Long userId;
    private String role;

    public AddMemberRequest() {
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }
}
