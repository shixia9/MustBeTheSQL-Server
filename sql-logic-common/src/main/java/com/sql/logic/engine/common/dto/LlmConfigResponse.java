package com.sql.logic.engine.common.dto;

import lombok.Data;

@Data
public class LlmConfigResponse {
    private Long id;
    private String configName;
    private String providerType;
    private String baseUrl;
    private String apiKeyMasked;  // e.g. "sk-***...***abc"
    private String modelName;
    private Boolean isDefault;
    private Integer status;
    private String strategyType;
    private String fallbackChain;
    private String circuitState;
    private String createTime;
    private String updateTime;
}