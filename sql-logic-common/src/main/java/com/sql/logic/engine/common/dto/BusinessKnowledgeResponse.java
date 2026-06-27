package com.sql.logic.engine.common.dto;

import lombok.Data;

@Data
public class BusinessKnowledgeResponse {
    private Long id;
    private Long connectionId;
    private String vectorType;
    private String term;
    private String description;
    private String synonyms;
    private String question;
    private String answer;
    private Integer status;
    private String createTime;
    private String updateTime;
}