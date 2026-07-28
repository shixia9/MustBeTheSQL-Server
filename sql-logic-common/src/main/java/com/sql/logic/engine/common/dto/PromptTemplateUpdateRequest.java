package com.sql.logic.engine.common.dto;

import lombok.Data;

@Data
public class PromptTemplateUpdateRequest {
    private Long id;
    private String name;
    private String content;
    private String description;
    private Integer status;
}
