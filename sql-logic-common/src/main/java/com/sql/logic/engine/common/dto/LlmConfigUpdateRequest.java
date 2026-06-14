package com.sql.logic.engine.common.dto;

import lombok.Data;

@Data
public class LlmConfigUpdateRequest {
    private Long configId;         // Required: ID of the config to update
    private String configName;     // Optional
    private String providerType;   // Optional
    private String baseUrl;        // Optional
    private String apiKey;         // Optional (null = keep existing)
    private String modelName;     // Optional
    private Boolean isDefault;     // Optional
    private Integer status;        // Optional: 0 = inactive, 1 = active
}