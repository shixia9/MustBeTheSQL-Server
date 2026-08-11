package com.sql.logic.engine.common.dto;

import lombok.Data;

@Data
public class ActiveConnectorCreateRequest {
    private Long templateId;
    private Long connectionId;
    private String name;
}
