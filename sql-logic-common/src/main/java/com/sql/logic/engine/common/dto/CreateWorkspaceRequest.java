package com.sql.logic.engine.common.dto;

public class CreateWorkspaceRequest {

    private String name;
    private String description;

    public CreateWorkspaceRequest() {
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
