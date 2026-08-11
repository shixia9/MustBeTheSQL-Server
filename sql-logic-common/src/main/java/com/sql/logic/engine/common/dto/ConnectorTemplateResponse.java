package com.sql.logic.engine.common.dto;

import lombok.Data;

@Data
public class ConnectorTemplateResponse {
    private Long id;
    private String name;
    private String connectorType;
    private String config;
    private String description;
    private Integer status;
    private String createTime;
    private String updateTime;
}
