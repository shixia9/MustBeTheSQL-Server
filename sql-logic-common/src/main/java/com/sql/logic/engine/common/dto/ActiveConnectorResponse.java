package com.sql.logic.engine.common.dto;

import lombok.Data;

@Data
public class ActiveConnectorResponse {
    private Long id;
    private Long templateId;
    private Long connectionId;
    private String name;
    private Integer status;
    private String createTime;
    private String updateTime;
}
