package com.sql.logic.engine.common.dto;

import lombok.Data;

@Data
public class PromptTemplateCreateRequest {
    private String name;
    private String content;
    private String description;
}
